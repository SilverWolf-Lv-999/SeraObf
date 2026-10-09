package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.utility.NameUtility;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class StringEncryptor extends ClassVisitor {

    private final String owner;
    private final String decryptName;
    private final int methodAccess;
    private final boolean interfaceOwner;
    private final boolean framesRequired;
    private final boolean encrypt;
    private final Map<String, String> classMapping;
    private final Map<String, String> replacements;
    private boolean used;
    private int literalIndex;

    public StringEncryptor(ClassVisitor classVisitor, String owner, String decryptName, int methodAccess, boolean interfaceOwner, int classVersion, boolean encrypt, Map<String, String> classMapping, Map<String, String> replacements) {
        super(Opcodes.ASM9, classVisitor);
        this.owner = owner;
        this.decryptName = decryptName;
        this.methodAccess = methodAccess;
        this.interfaceOwner = interfaceOwner;
        this.framesRequired = (classVersion & 0xFFFF) >= Opcodes.V1_6;
        this.encrypt = encrypt;
        this.classMapping = classMapping;
        this.replacements = replacements;
    }

    public void emit(MethodVisitor methodVisitor, String text) {
        String value = adapt(replacements.getOrDefault(text, text));
        if (!encrypt) {
            methodVisitor.visitLdcInsn(value);
            return;
        }
        used = true;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int key = (literalIndex++ * 0x1F + 0x5F) & 0xFF;
        byte[] encoded = new byte[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            encoded[index] = (byte) (bytes[index] ^ (key + index * 0x1F));
        }
        methodVisitor.visitLdcInsn(new String(encoded, StandardCharsets.ISO_8859_1));
        methodVisitor.visitLdcInsn(key);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, owner, decryptName, "(Ljava/lang/String;I)Ljava/lang/String;", interfaceOwner);
    }

    private String adapt(String text) {
        String internalName = text.replace('.', '/');
        String mapped = classMapping.get(internalName);
        if (mapped == null || mapped.equals(internalName)) {
            return text;
        }
        return NameUtility.binaryName(mapped);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        return methodVisitor == null ? null : new StringConstantMethodVisitor(methodVisitor, this);
    }

    @Override
    public void visitEnd() {
        if (used) {
            MethodNode method = new MethodNode(methodAccess, decryptName, "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
            buildDecryptor(method.instructions);
            method.accept(cv);
        }
        super.visitEnd();
    }

    private void buildDecryptor(InsnList instructions) {
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "ISO_8859_1", "Ljava/nio/charset/Charset;"));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", false));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        instructions.add(new InsnNode(Opcodes.ICONST_0));
        instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        instructions.add(loopStart);
        if (framesRequired) {
            instructions.add(new FrameNode(Opcodes.F_APPEND, 2, new Object[]{"[B", Opcodes.INTEGER}, 0, null));
        }
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new InsnNode(Opcodes.BALOAD));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        instructions.add(new LdcInsnNode(0x1F));
        instructions.add(new InsnNode(Opcodes.IMUL));
        instructions.add(new InsnNode(Opcodes.IADD));
        instructions.add(new InsnNode(Opcodes.IXOR));
        instructions.add(new InsnNode(Opcodes.I2B));
        instructions.add(new InsnNode(Opcodes.BASTORE));
        instructions.add(new IincInsnNode(3, 1));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        instructions.add(loopEnd);
        if (framesRequired) {
            instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        }
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false));
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false));
        instructions.add(new InsnNode(Opcodes.ARETURN));
    }
}
