package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.common.config.ClientConfig;
import io.github.seraphina.obf.utility.NameUtility;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MethodSplitter {

    private final ClientConfig config;
    private final Map<String, ClassNode> originals;
    private final Map<String, ClassNode> remapped;
    private final Map<String, String> classMapping;
    private final Map<String, String> originalNames;
    private final ClassLoader classLoader;
    private final NameAllocator nameAllocator;
    private final Set<String> allocatedClassNames;
    private final List<ClassNode> generatedClasses = new ArrayList<>();
    private final Map<String, ClassNode> holders = new HashMap<>();
    private final Map<String, Set<String>> chunkNames = new HashMap<>();
    private final Map<String, Member> memberCache = new HashMap<>();
    private final Map<String, Class<?>> typeCache = new HashMap<>();
    private int splitMethodCount;

    public MethodSplitter(ClientConfig config, Map<String, ClassNode> originals, Map<String, ClassNode> remapped, Map<String, String> classMapping, Map<String, String> originalNames, ClassLoader classLoader, NameAllocator nameAllocator, Set<String> allocatedClassNames) {
        this.config = config;
        this.originals = originals;
        this.remapped = remapped;
        this.classMapping = classMapping;
        this.originalNames = originalNames;
        this.classLoader = classLoader;
        this.nameAllocator = nameAllocator;
        this.allocatedClassNames = allocatedClassNames;
    }

    public void splitAll() {
        for (ClassNode node : new ArrayList<>(remapped.values())) {
            ClassNode originalNode = originals.get(originalNames.get(node.name));
            if (originalNode == null || (node.access & Opcodes.ACC_ANNOTATION) != 0) {
                continue;
            }
            if (isMixinClass(node)) {
                continue;
            }
            if ((node.access & Opcodes.ACC_INTERFACE) != 0 && (node.version & 0xFFFF) < Opcodes.V1_8) {
                continue;
            }
            List<MethodNode> methods = new ArrayList<>(node.methods);
            for (int index = 0; index < methods.size() && index < originalNode.methods.size(); index++) {
                splitMethod(node, originalNode, methods.get(index), originalNode.methods.get(index));
            }
        }
    }

    private boolean isMixinClass(ClassNode node) {
        if (node.name.toLowerCase(java.util.Locale.ROOT).contains("mixin")) {
            return true;
        }
        for (java.util.List<AnnotationNode> annotations : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) {
                continue;
            }
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc != null && annotation.desc.toLowerCase(java.util.Locale.ROOT).contains("mixin")) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<ClassNode> getGeneratedClasses() {
        return generatedClasses;
    }

    public int getSplitMethodCount() {
        return splitMethodCount;
    }

    private void splitMethod(ClassNode node, ClassNode originalNode, MethodNode method, MethodNode originalMethod) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0) {
            return;
        }
        if (method.name.equals("<init>") || !method.tryCatchBlocks.isEmpty()) {
            return;
        }
        List<AbstractInsnNode> real = realInstructions(method);
        if (real.size() < 24 || real.size() != realInstructions(originalMethod).size()) {
            return;
        }
        Map<LabelNode, Integer> labels = labelPositions(method);
        int[] crossing = new int[real.size() + 1];
        boolean branching = false;
        for (int position = 0; position < real.size(); position++) {
            AbstractInsnNode instruction = real.get(position);
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                return;
            }
            if (instruction instanceof JumpInsnNode) {
                branching = true;
                markCrossing(crossing, real.size(), position, labels.getOrDefault(((JumpInsnNode) instruction).label, real.size()));
            } else if (instruction instanceof TableSwitchInsnNode) {
                branching = true;
                TableSwitchInsnNode tableSwitch = (TableSwitchInsnNode) instruction;
                markCrossing(crossing, real.size(), position, labels.getOrDefault(tableSwitch.dflt, real.size()));
                for (LabelNode label : tableSwitch.labels) {
                    markCrossing(crossing, real.size(), position, labels.getOrDefault(label, real.size()));
                }
            } else if (instruction instanceof LookupSwitchInsnNode) {
                branching = true;
                LookupSwitchInsnNode lookupSwitch = (LookupSwitchInsnNode) instruction;
                markCrossing(crossing, real.size(), position, labels.getOrDefault(lookupSwitch.dflt, real.size()));
                for (LabelNode label : lookupSwitch.labels) {
                    markCrossing(crossing, real.size(), position, labels.getOrDefault(label, real.size()));
                }
            }
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                return;
            }
            boolean terminal = opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW;
            if (terminal != (position == real.size() - 1)) {
                return;
            }
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.PUTSTATIC || field.getOpcode() == Opcodes.PUTFIELD) {
                    Member member = resolve(field.owner, field.name, field.desc, false);
                    if (member == null || Modifier.isFinal(member.access)) {
                        return;
                    }
                }
            }
        }
        if (branching && !config.rebuildStackMapFrames) {
            return;
        }
        Frame<BasicValue>[] frames = analyze(originalNode, originalMethod);
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < originalMethod.instructions.size(); index++) {
            AbstractInsnNode instruction = originalMethod.instructions.get(index);
            if (instruction instanceof LabelNode || instruction instanceof LineNumberNode || instruction instanceof FrameNode) {
                continue;
            }
            positions.add(index);
        }
        List<Integer> chunkPositions = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode || instruction instanceof LineNumberNode || instruction instanceof FrameNode) {
                continue;
            }
            chunkPositions.add(index);
        }
        if (frames == null || frames.length != originalMethod.instructions.size() || positions.size() != real.size() || chunkPositions.size() != real.size()) {
            return;
        }
        for (int position = 0; position < real.size(); position++) {
            AbstractInsnNode instruction = real.get(position);
            Frame<BasicValue> frame = frames[positions.get(position)];
            if (frame == null) {
                return;
            }
            if (instruction instanceof VarInsnNode) {
                int opcode = instruction.getOpcode();
                if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD && !usable(frame.getLocal(((VarInsnNode) instruction).var))) {
                    return;
                }
            }
        }
        boolean[] blocked = new boolean[real.size()];
        int active = 0;
        for (int position = 1; position < real.size(); position++) {
            active += crossing[position];
            blocked[position] = active > 0;
        }
        List<Integer> boundaries = chooseBoundaries(node, real, blocked, positions, frames);
        if (boundaries.isEmpty()) {
            return;
        }
        boolean nestAllowed = (node.version & 0xFFFF) >= Opcodes.V11 && remapped.containsKey(nestHost(node.name));
        ClassNode holder = config.randomSplitClass && movable(node, method, nestAllowed) ? provideHolder(node) : null;
        List<MethodNode> chunks = new ArrayList<>();
        List<String> chunkOwners = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex <= boundaries.size(); chunkIndex++) {
            int start = chunkIndex == 0 ? 0 : boundaries.get(chunkIndex - 1);
            int end = chunkIndex == boundaries.size() ? real.size() : boundaries.get(chunkIndex);
            boolean last = chunkIndex == boundaries.size();
            ClassNode target = holder == null ? node : holder;
            MethodNode chunk = createChunk(node, method, target, positions, chunkPositions, frames, start, end, last);
            target.methods.add(chunk);
            chunks.add(chunk);
            chunkOwners.add(target.name);
        }
        rewriteMethod(node, method, chunks, chunkOwners, Math.max(originalMethod.maxLocals, argumentSlots(method)));
        splitMethodCount++;
    }

    private void rewriteMethod(ClassNode node, MethodNode method, List<MethodNode> chunks, List<String> chunkOwners, int contextSlots) {
        InsnList body = new InsnList();
        int contextSlot = Math.max(Math.max(method.maxLocals, contextSlots), argumentSlots(method));
        pushInt(body, contextSlot);
        body.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        body.add(new VarInsnNode(Opcodes.ASTORE, contextSlot));
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            body.add(new VarInsnNode(Opcodes.ALOAD, contextSlot));
            pushInt(body, 0);
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            body.add(new InsnNode(Opcodes.AASTORE));
            slot = 1;
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            body.add(new VarInsnNode(Opcodes.ALOAD, contextSlot));
            pushInt(body, slot);
            body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            box(body, argument);
            body.add(new InsnNode(Opcodes.AASTORE));
            slot += argument.getSize();
        }
        boolean interfaceOwner = (node.access & Opcodes.ACC_INTERFACE) != 0;
        for (int index = 0; index < chunks.size(); index++) {
            MethodNode chunk = chunks.get(index);
            body.add(new VarInsnNode(Opcodes.ALOAD, contextSlot));
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, chunkOwners.get(index), chunk.name, chunk.desc, interfaceOwner && chunkOwners.get(index).equals(node.name)));
        }
        Type returnType = Type.getReturnType(method.desc);
        body.add(new InsnNode(returnType.getSort() == Type.VOID ? Opcodes.RETURN : returnType.getOpcode(Opcodes.IRETURN)));
        method.instructions = body;
        method.tryCatchBlocks = new ArrayList<>();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = 0;
        method.maxLocals = 0;
    }

    private Map<LabelNode, Integer> labelPositions(MethodNode method) {
        Map<LabelNode, Integer> labels = new HashMap<>();
        int position = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof LabelNode) {
                labels.putIfAbsent((LabelNode) instruction, position);
                continue;
            }
            if (instruction instanceof LineNumberNode || instruction instanceof FrameNode) {
                continue;
            }
            position++;
        }
        return labels;
    }

    private void markCrossing(int[] crossing, int size, int from, int to) {
        int low = Math.min(from, to) + 1;
        int high = Math.min(Math.max(from, to), size - 1);
        if (low > high) {
            return;
        }
        crossing[low]++;
        crossing[high + 1]--;
    }

    private List<Integer> chooseBoundaries(ClassNode node, List<AbstractInsnNode> real, boolean[] blocked, List<Integer> positions, Frame<BasicValue>[] frames) {
        int minimum = 12;
        int maximumChunks = 3;
        List<Integer> candidates = new ArrayList<>();
        for (int position = 1; position < real.size() - 1; position++) {
            if (frames[positions.get(position)].getStackSize() == 0 && !blocked[position]) {
                candidates.add(position);
            }
        }
        Collections.shuffle(candidates, new Random(node.name.hashCode() * 31L + real.size()));
        List<Integer> boundaries = new ArrayList<>();
        for (Integer candidate : candidates) {
            if (boundaries.size() >= maximumChunks - 1) {
                break;
            }
            if (candidate < minimum || real.size() - candidate < minimum) {
                continue;
            }
            boolean allowed = true;
            for (Integer chosen : boundaries) {
                if (Math.abs(chosen - candidate) < minimum) {
                    allowed = false;
                    break;
                }
            }
            if (allowed) {
                boundaries.add(candidate);
            }
        }
        Collections.sort(boundaries);
        return boundaries;
    }

    private MethodNode createChunk(ClassNode node, MethodNode method, ClassNode target, List<Integer> positions, List<Integer> chunkPositions, Frame<BasicValue>[] frames, int start, int end, boolean last) {
        boolean nested = target != node;
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        String descriptor = "([Ljava/lang/Object;)" + (last ? Type.getReturnType(method.desc).getDescriptor() : "V");
        MethodNode chunk = new MethodNode(access, nextChunkName(target), descriptor, null, null);
        int previous = start == 0 ? -1 : chunkPositions.get(start - 1);
        for (int position = start; position < end; position++) {
            int index = chunkPositions.get(position);
            for (int scan = previous + 1; scan < index; scan++) {
                AbstractInsnNode pseudo = method.instructions.get(scan);
                if (pseudo instanceof LabelNode) {
                    chunk.instructions.add(pseudo);
                }
            }
            previous = index;
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                if (variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD) {
                    loadLocal(chunk.instructions, frames[positions.get(position)].getLocal(variable.var), variable.var);
                } else {
                    storeLocal(chunk.instructions, variable.getOpcode(), variable.var);
                }
            } else if (instruction instanceof IincInsnNode) {
                IincInsnNode increment = (IincInsnNode) instruction;
                chunk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                pushInt(chunk.instructions, increment.var);
                chunk.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                pushInt(chunk.instructions, increment.var);
                chunk.instructions.add(new InsnNode(Opcodes.AALOAD));
                chunk.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                chunk.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                pushInt(chunk.instructions, increment.incr);
                chunk.instructions.add(new InsnNode(Opcodes.IADD));
                box(chunk.instructions, Type.INT_TYPE);
                chunk.instructions.add(new InsnNode(Opcodes.AASTORE));
            } else {
                chunk.instructions.add(instruction);
            }
        }
        if (!last) {
            chunk.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        return chunk;
    }

    private void loadLocal(InsnList instructions, BasicValue value, int slot) {
        Type type = value.getType();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(instructions, slot);
        instructions.add(new InsnNode(Opcodes.AALOAD));
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                break;
            case Type.FLOAT:
                instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
                break;
            case Type.LONG:
                instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
                break;
            case Type.DOUBLE:
                instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
                break;
            default:
                instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, mappedType(type)));
        }
    }

    private void storeLocal(InsnList instructions, int opcode, int slot) {
        String wrapper = null;
        String valueOf = null;
        int loadOpcode = Opcodes.ALOAD;
        if (opcode == Opcodes.ISTORE) {
            wrapper = "java/lang/Integer";
            valueOf = "(I)Ljava/lang/Integer;";
            loadOpcode = Opcodes.ILOAD;
        } else if (opcode == Opcodes.FSTORE) {
            wrapper = "java/lang/Float";
            valueOf = "(F)Ljava/lang/Float;";
            loadOpcode = Opcodes.FLOAD;
        } else if (opcode == Opcodes.LSTORE) {
            wrapper = "java/lang/Long";
            valueOf = "(J)Ljava/lang/Long;";
            loadOpcode = Opcodes.LLOAD;
        } else if (opcode == Opcodes.DSTORE) {
            wrapper = "java/lang/Double";
            valueOf = "(D)Ljava/lang/Double;";
            loadOpcode = Opcodes.DLOAD;
        }
        instructions.add(new VarInsnNode(opcode, 1));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(instructions, slot);
        instructions.add(new VarInsnNode(loadOpcode, 1));
        if (wrapper != null) {
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf", valueOf, false));
        }
        instructions.add(new InsnNode(Opcodes.AASTORE));
    }

    private void box(InsnList instructions, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                break;
            case Type.FLOAT:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
                break;
            case Type.LONG:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                break;
            case Type.DOUBLE:
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
                break;
            default:
        }
    }

    private void pushInt(InsnList instructions, int value) {
        instructions.add(new LdcInsnNode(value));
    }

    private String mappedType(Type type) {
        if (type.getSort() == Type.ARRAY) {
            StringBuilder builder = new StringBuilder();
            for (int dimension = 0; dimension < type.getDimensions(); dimension++) {
                builder.append('[');
            }
            Type element = type.getElementType();
            builder.append(element.getSort() == Type.OBJECT ? "L" + classMapping.getOrDefault(element.getInternalName(), element.getInternalName()) + ";" : element.getDescriptor());
            return builder.toString();
        }
        return classMapping.getOrDefault(type.getInternalName(), type.getInternalName());
    }

    private int argumentSlots(MethodNode method) {
        int slots = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
        return (method.access & Opcodes.ACC_STATIC) != 0 ? slots : slots + 1;
    }

    private List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction instanceof LabelNode || instruction instanceof LineNumberNode || instruction instanceof FrameNode) {
                continue;
            }
            instructions.add(instruction);
        }
        return instructions;
    }

    private Frame<BasicValue>[] analyze(ClassNode node, MethodNode method) {
        try {
            List<Type> interfaces = new ArrayList<>();
            for (String interfaceName : node.interfaces) {
                interfaces.add(Type.getObjectType(interfaceName));
            }
            SimpleVerifier verifier = new SimpleVerifier(Type.getObjectType(node.name), node.superName == null ? null : Type.getObjectType(node.superName), interfaces, (node.access & Opcodes.ACC_INTERFACE) != 0);
            verifier.setClassLoader(classLoader);
            return new Analyzer<>(verifier).analyzeAndComputeMaxs(node.name, method);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private boolean usable(BasicValue value) {
        if (value == null || value == BasicValue.UNINITIALIZED_VALUE || value.getType() == null) {
            return false;
        }
        Type type = value.getType();
        return type.getSort() != Type.OBJECT || !type.getInternalName().equals("null");
    }

    private boolean movable(ClassNode node, MethodNode method, boolean nestAllowed) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL && !call.name.equals("<init>")) {
                    return false;
                }
                if (!accessible(node, call.owner, call.name, call.desc, true, nestAllowed)) {
                    return false;
                }
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (!accessible(node, field.owner, field.name, field.desc, false, nestAllowed)) {
                    return false;
                }
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                if (!dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                    return false;
                }
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle || argument instanceof ConstantDynamic) {
                        return false;
                    }
                }
            } else if (instruction instanceof LdcInsnNode) {
                Object constant = ((LdcInsnNode) instruction).cst;
                if (constant instanceof Handle || constant instanceof ConstantDynamic) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean accessible(ClassNode node, String owner, String name, String descriptor, boolean method, boolean nestAllowed) {
        Member member = resolve(owner, name, descriptor, method);
        if (member == null) {
            return false;
        }
        if (Modifier.isPublic(member.access)) {
            return true;
        }
        if (Modifier.isPrivate(member.access)) {
            return nestAllowed && nestHost(member.declaringClass).equals(nestHost(node.name));
        }
        return NameUtility.packageName(member.declaringClass).equals(NameUtility.packageName(node.name));
    }

    private Member resolve(String owner, String name, String descriptor, boolean method) {
        String key = (method ? "M" : "F") + owner + '#' + name + descriptor;
        if (memberCache.containsKey(key)) {
            return memberCache.get(key);
        }
        Member result = null;
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            ClassNode current0 = remapped.get(current);
            if (current0 != null) {
                if (method) {
                    for (MethodNode candidate : current0.methods) {
                        if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) {
                            result = new Member(current, candidate.access);
                            break;
                        }
                    }
                } else {
                    for (FieldNode candidate : current0.fields) {
                        if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) {
                            result = new Member(current, candidate.access);
                            break;
                        }
                    }
                }
                if (result != null) {
                    break;
                }
                if (current0.superName != null) {
                    queue.add(current0.superName);
                }
                queue.addAll(current0.interfaces);
                continue;
            }
            Class<?> type = loadType(current);
            if (type == null) {
                break;
            }
            try {
                if (method) {
                    for (java.lang.reflect.Method candidate : type.getDeclaredMethods()) {
                        if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).equals(descriptor)) {
                            result = new Member(current, candidate.getModifiers());
                            break;
                        }
                    }
                } else {
                    for (java.lang.reflect.Field candidate : type.getDeclaredFields()) {
                        if (candidate.getName().equals(name) && Type.getDescriptor(candidate.getType()).equals(descriptor)) {
                            result = new Member(current, candidate.getModifiers());
                            break;
                        }
                    }
                }
            } catch (Throwable throwable) {
                break;
            }
            if (result != null) {
                break;
            }
            if (type.getSuperclass() != null) {
                queue.add(NameUtility.internalName(type.getSuperclass().getName()));
            }
            for (Class<?> interfaceType : type.getInterfaces()) {
                queue.add(NameUtility.internalName(interfaceType.getName()));
            }
        }
        memberCache.put(key, result);
        return result;
    }

    private Class<?> loadType(String internalName) {
        if (typeCache.containsKey(internalName)) {
            return typeCache.get(internalName);
        }
        Class<?> type = null;
        try {
            type = Class.forName(NameUtility.binaryName(internalName), false, classLoader);
        } catch (Throwable throwable) {
            type = null;
        }
        typeCache.put(internalName, type);
        return type;
    }

    private String nestHost(String className) {
        ClassNode node = remapped.get(className);
        return node != null && node.nestHostClass != null ? node.nestHostClass : className;
    }

    private ClassNode provideHolder(ClassNode node) {
        ClassNode holder = holders.get(node.name);
        if (holder != null) {
            return holder;
        }
        String host = nestHost(node.name);
        ClassNode hostNode = remapped.get(host);
        if (hostNode == null) {
            return null;
        }
        ClassNode generated = new ClassNode();
        generated.version = node.version;
        generated.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        String prefix = NameUtility.packageName(node.name);
        String candidate;
        do {
            candidate = prefix + nameAllocator.nextClassName();
        } while (!allocatedClassNames.add(candidate));
        generated.name = candidate;
        generated.superName = "java/lang/Object";
        if ((node.version & 0xFFFF) >= Opcodes.V11) {
            generated.nestHostClass = host;
            if (hostNode.nestMembers == null) {
                hostNode.nestMembers = new ArrayList<>();
            }
            hostNode.nestMembers.add(generated.name);
        }
        holders.put(node.name, generated);
        generatedClasses.add(generated);
        return generated;
    }

    private String nextChunkName(ClassNode target) {
        Set<String> used = chunkNames.computeIfAbsent(target.name, key -> {
            Set<String> names = new HashSet<>();
            for (MethodNode method : target.methods) {
                names.add(method.name);
            }
            return names;
        });
        if (config.randomSplitMethodName) {
            String candidate;
            do {
                candidate = nameAllocator.nextMethodName();
            } while (!used.add(candidate));
            return candidate;
        }
        int index = 1;
        String candidate;
        do {
            candidate = "$split" + index++;
        } while (!used.add(candidate));
        return candidate;
    }

    private static class Member {

        private final String declaringClass;
        private final int access;

        private Member(String declaringClass, int access) {
            this.declaringClass = declaringClass;
            this.access = access;
        }
    }
}
