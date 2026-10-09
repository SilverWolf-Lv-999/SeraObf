package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.common.config.ClientConfig;
import io.github.seraphina.obf.utility.NameUtility;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StringRewriter {

    private final ClientConfig config;
    private final ClassNode node;
    private final Map<String, String> classMapping;
    private final Map<String, String> replacements;
    private final NameAllocator nameAllocator;
    private final List<Literal> literals = new ArrayList<>();
    private boolean interfaceOwner;
    private boolean framesRequired;
    private String fieldName;
    private String decryptName;

    public StringRewriter(ClientConfig config, ClassNode node, Map<String, String> classMapping, Map<String, String> replacements, NameAllocator nameAllocator) {
        this.config = config;
        this.node = node;
        this.classMapping = classMapping;
        this.replacements = replacements;
        this.nameAllocator = nameAllocator;
    }

    public void rewrite() {
        collect();
        if (literals.isEmpty()) {
            return;
        }
        interfaceOwner = (node.access & Opcodes.ACC_INTERFACE) != 0;
        framesRequired = (node.version & 0xFFFF) >= Opcodes.V1_6;
        if (!config.encryptStrings || interfaceOwner && (node.version & 0xFFFF) < Opcodes.V1_8) {
            for (Literal literal : literals) {
                literal.write(literal.value);
            }
            return;
        }
        int position = 0;
        for (Literal literal : literals) {
            literal.offset = position;
            position += (literal.value.length() + 1) & ~1;
        }
        int[] data = new int[position >> 1];
        for (Literal literal : literals) {
            encode(data, literal);
        }
        fieldName = nextFieldName();
        decryptName = nextMethodName();
        node.fields.add(new FieldNode(interfaceOwner ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC : Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, fieldName, "[I", null, null));
        node.methods.add(buildDecryptor());
        InsnList prelude = fill(data);
        for (Literal literal : literals) {
            literal.replace(prelude);
        }
        initializer().instructions.insert(prelude);
    }

    private void collect() {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof LdcInsnNode)) {
                    continue;
                }
                Object constant = ((LdcInsnNode) instruction).cst;
                if (constant instanceof String) {
                    literals.add(new Literal(method, (LdcInsnNode) instruction, null, transform((String) constant)));
                }
            }
        }
        for (FieldNode field : node.fields) {
            if (!(field.value instanceof String)) {
                continue;
            }
            if ((field.access & Opcodes.ACC_STATIC) != 0) {
                literals.add(new Literal(null, null, field, transform((String) field.value)));
            } else if (config.encryptStrings) {
                field.value = null;
            }
        }
    }

    private String transform(String text) {
        String value = replacements.getOrDefault(text, text);
        if (!config.adaptClassStrings) {
            return value;
        }
        String internalName = value.replace('.', '/');
        String mapped = classMapping.get(internalName);
        if (mapped == null || mapped.equals(internalName)) {
            return value;
        }
        return NameUtility.binaryName(mapped);
    }

    private void encode(int[] data, Literal literal) {
        String value = literal.value;
        int base = literal.offset >> 1;
        for (int index = 0; index * 2 < value.length(); index++) {
            int high = value.charAt(index * 2);
            int low = index * 2 + 1 < value.length() ? value.charAt(index * 2 + 1) : 0;
            int position = base + index;
            data[position] = ((high << 16) | low) ^ mask(position);
        }
    }

    private int mask(int position) {
        return seed() + position * 0x9E3779B9;
    }

    private int seed() {
        return node.name.hashCode() ^ 0x5F3A91B7;
    }

    private InsnList fill(int[] data) {
        InsnList instructions = new InsnList();
        pushInt(instructions, data.length);
        instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, fieldName, "[I"));
        if (data.length <= 4096) {
            instructions.add(stores(data, 0, data.length));
            return instructions;
        }
        for (int start = 0; start < data.length; start += 4096) {
            String name = nextMethodName();
            node.methods.add(buildFiller(name, data, start, Math.min(start + 4096, data.length)));
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, name, "()V", interfaceOwner));
        }
        return instructions;
    }

    private InsnList stores(int[] data, int start, int end) {
        InsnList instructions = new InsnList();
        instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, fieldName, "[I"));
        for (int index = start; index < end; index++) {
            instructions.add(new InsnNode(Opcodes.DUP));
            pushInt(instructions, index);
            pushInt(instructions, data[index]);
            instructions.add(new InsnNode(Opcodes.IASTORE));
        }
        instructions.add(new InsnNode(Opcodes.POP));
        return instructions;
    }

    private MethodNode buildFiller(String name, int[] data, int start, int end) {
        int access = interfaceOwner && (node.version & 0xFFFF) < Opcodes.V9
                ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                : Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        MethodNode method = new MethodNode(access, name, "()V", null, null);
        method.instructions.add(stores(data, start, end));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 4;
        method.maxLocals = 0;
        return method;
    }

    private MethodNode buildDecryptor() {
        int access = interfaceOwner && (node.version & 0xFFFF) < Opcodes.V9
                ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC
                : Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        MethodNode method = new MethodNode(access, decryptName, "([III)Ljava/lang/String;", null, null);
        InsnList instructions = method.instructions;
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        Object[] locals = {"[I", Opcodes.INTEGER, Opcodes.INTEGER, "[C", Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER};
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(instructions, 1);
        instructions.add(new InsnNode(Opcodes.IADD));
        pushInt(instructions, -2);
        instructions.add(new InsnNode(Opcodes.IAND));
        instructions.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_CHAR));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pushInt(instructions, 1);
        instructions.add(new InsnNode(Opcodes.IUSHR));
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 4));
        pushInt(instructions, 0);
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        pushInt(instructions, 0);
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        instructions.add(loopStart);
        if (framesRequired) {
            instructions.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 0, new Object[0]));
        }
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        instructions.add(new InsnNode(Opcodes.IALOAD));
        pushInt(instructions, seed());
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        instructions.add(new LdcInsnNode(0x9E3779B9));
        instructions.add(new InsnNode(Opcodes.IMUL));
        instructions.add(new InsnNode(Opcodes.IADD));
        instructions.add(new InsnNode(Opcodes.IXOR));
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 6));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        pushInt(instructions, 16);
        instructions.add(new InsnNode(Opcodes.IUSHR));
        instructions.add(new InsnNode(Opcodes.I2C));
        instructions.add(new InsnNode(Opcodes.CASTORE));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        pushInt(instructions, 1);
        instructions.add(new InsnNode(Opcodes.IADD));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        instructions.add(new InsnNode(Opcodes.I2C));
        instructions.add(new InsnNode(Opcodes.CASTORE));
        instructions.add(new IincInsnNode(5, 2));
        instructions.add(new IincInsnNode(4, 1));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        instructions.add(loopEnd);
        if (framesRequired) {
            instructions.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 0, new Object[0]));
        }
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        pushInt(instructions, 0);
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([CII)V", false));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 4;
        method.maxLocals = 7;
        return method;
    }

    private MethodNode initializer() {
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                return method;
            }
        }
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return method;
    }

    private String nextFieldName() {
        Set<String> used = new HashSet<>();
        for (FieldNode field : node.fields) {
            used.add(field.name);
        }
        String name;
        do {
            name = nameAllocator.nextFieldName();
        } while (!used.add(name));
        return name;
    }

    private String nextMethodName() {
        Set<String> used = new HashSet<>();
        for (MethodNode method : node.methods) {
            used.add(method.name);
        }
        String name;
        do {
            name = nameAllocator.nextMethodName();
        } while (!used.add(name));
        return name;
    }

    private static void pushInt(InsnList instructions, int value) {
        if (value >= -128 && value <= 127) {
            instructions.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= -32768 && value <= 32767) {
            instructions.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            instructions.add(new LdcInsnNode(value));
        }
    }

    private class Literal {

        private final MethodNode method;
        private final LdcInsnNode instruction;
        private final FieldNode field;
        private final String value;
        private int offset;

        private Literal(MethodNode method, LdcInsnNode instruction, FieldNode field, String value) {
            this.method = method;
            this.instruction = instruction;
            this.field = field;
            this.value = value;
        }

        private void write(String text) {
            if (field == null) {
                instruction.cst = text;
                return;
            }
            field.value = text;
        }

        private void replace(InsnList assignments) {
            if (field != null) {
                field.value = null;
                load(assignments);
                assignments.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, field.desc));
                return;
            }
            InsnList replacement = new InsnList();
            load(replacement);
            method.instructions.insert(instruction, replacement);
            method.instructions.remove(instruction);
        }

        private void load(InsnList target) {
            target.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, fieldName, "[I"));
            pushInt(target, offset);
            pushInt(target, value.length());
            target.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, decryptName, "([III)Ljava/lang/String;", interfaceOwner));
        }
    }
}
