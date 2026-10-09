package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.common.config.ClientConfig;
import io.github.seraphina.obf.utility.JarUtility;
import io.github.seraphina.obf.utility.NameUtility;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

public class Obfuscator {

    private final ClientConfig config;
    private final Consumer<String> logger;
    private final IntConsumer progress;
    private final Map<String, String> classMapping = new HashMap<>();
    private final Map<String, String> methodMapping = new HashMap<>();
    private final Map<String, String> fieldMapping = new HashMap<>();
    private final Map<String, String> memberGroups = new HashMap<>();
    private final Map<String, String> outerNames = new HashMap<>();
    private final Set<String> allocatedClassNames = new HashSet<>();
    private final List<String> keptClassRules = new ArrayList<>();
    private final List<String> keptMemberRules = new ArrayList<>();
    private final Map<String, String> stringReplacements = new HashMap<>();
    private final Set<String> serviceProviders = new HashSet<>();
    private final Set<String> stringReferencedClasses = new HashSet<>();
    private final Set<String> reflectiveRewrittenNames = new HashSet<>();
    private final Set<String> reflectiveNamePins = new HashSet<>();
    private final Map<String, Set<String>> reflectivePins = new HashMap<>();
    private final List<ReflectiveString> reflectiveStrings = new ArrayList<>();
    private final Map<String, String> classGroups = new HashMap<>();
    private final Map<String, String> classGroupPaths = new HashMap<>();
    private final Map<String, String> commonSuperCache = new HashMap<>();
    private NameAllocator nameAllocator;
    private ClassLoader frameClassLoader;
    private Map<String, ClassNode> frameClasses;
    private String classPathPrefix = "";

    public Obfuscator(ClientConfig config, Consumer<String> logger, IntConsumer progress) {
        this.config = config;
        this.logger = logger;
        this.progress = progress;
    }

    public void obfuscate() throws Exception {
        long startTime = System.currentTimeMillis();
        nameAllocator = new NameAllocator(config.dictionarySeed);
        logger.accept("词典种子: " + nameAllocator.getSeed());
        File inputFile = new File(config.inputJar);
        File outputFile = new File(config.outputJar);
        logger.accept("读取输入: " + inputFile.getAbsolutePath());
        Map<String, byte[]> entries = JarUtility.readEntries(inputFile);
        Map<String, ClassNode> classes = readClasses(entries);
        logger.accept("条目 " + entries.size() + " 个, 类 " + classes.size() + " 个");
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith("META-INF/services/")) {
                continue;
            }
            String serviceName = entry.getKey().substring("META-INF/services/".length());
            if (config.keepServiceNames && !serviceName.isEmpty()) {
                serviceProviders.add(NameUtility.internalName(serviceName));
            }
            for (String line : new String(entry.getValue(), StandardCharsets.UTF_8).split("[\\r\\n]+")) {
                String name = line.trim();
                if (config.keepServiceNames && !name.isEmpty() && !name.startsWith("#")) {
                    serviceProviders.add(NameUtility.internalName(name));
                }
            }
        }
        if (config.keepServiceNames && !serviceProviders.isEmpty()) {
            logger.accept("服务提供者类: " + serviceProviders.size() + " 个 (保留类名)");
        } else if (!config.keepServiceNames) {
            logger.accept("服务提供者类: 参与混淆 (服务文件同步改写)");
        }
        logger.accept("混淆类路径: " + (config.renameClasses && !config.obfuscateClassPath.isBlank() ? config.obfuscateClassPath.trim() : "保持原包"));
        for (String rule : config.keepRules.split("[,;\\s]+")) {
            if (rule.isEmpty()) {
                continue;
            }
            int hash = rule.indexOf('#');
            if (hash < 0) {
                keptClassRules.add(NameUtility.internalName(rule));
            } else {
                keptMemberRules.add(NameUtility.internalName(rule.substring(0, hash)) + '#' + rule.substring(hash + 1));
            }
        }
        if (!keptClassRules.isEmpty() || !keptMemberRules.isEmpty()) {
            logger.accept("保留规则: " + (keptClassRules.size() + keptMemberRules.size()) + " 条");
        }
        for (String rule : config.stringReplacements.split("[;\\r\\n]+")) {
            int index = rule.indexOf('=');
            if (index > 0) {
                stringReplacements.put(rule.substring(0, index), rule.substring(index + 1));
            }
        }
        if (!stringReplacements.isEmpty()) {
            logger.accept("字符串替换: " + stringReplacements.size() + " 条");
        }
        progress.accept(10);

        try (URLClassLoader classLoader = createClassLoader(inputFile)) {
            ClassHierarchy hierarchy = new ClassHierarchy(classes, classLoader);
            if (config.splitSubPath && config.renameClasses) {
                buildSubPathGroups(hierarchy);
            }
            buildClassMapping(hierarchy);
            if (config.renameMembers) {
                collectStringReferencedClasses(hierarchy);
                collectReflectiveStrings(hierarchy);
                buildMemberMapping(hierarchy);
                applyReflectiveStrings();
            }
            int renamedClasses = 0;
            for (Map.Entry<String, String> mapping : classMapping.entrySet()) {
                if (!mapping.getKey().equals(mapping.getValue())) {
                    renamedClasses++;
                }
            }
            logger.accept("重命名类 " + renamedClasses + " 个, 方法 " + methodMapping.size() + " 个, 字段 " + fieldMapping.size() + " 个");
            progress.accept(45);

            NameRemapper remapper = new NameRemapper(classMapping, methodMapping, fieldMapping, hierarchy);
            writeJar(entries, classes, remapper, classLoader, outputFile);
        }
        progress.accept(100);
        logger.accept("混淆完成, 耗时 " + (System.currentTimeMillis() - startTime) + " ms");
        logger.accept("输出: " + outputFile.getAbsolutePath());
    }

    private URLClassLoader createClassLoader(File inputFile) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(inputFile.toURI().toURL());
        if (!config.libraryJars.isBlank()) {
            for (String path : config.libraryJars.split(File.pathSeparator)) {
                if (!path.isBlank()) {
                    urls.add(new File(path.trim()).toURI().toURL());
                }
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader());
    }

    private Map<String, ClassNode> readClasses(Map<String, byte[]> entries) {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".class") || name.startsWith("META-INF/versions/") || name.endsWith("module-info.class")) {
                continue;
            }
            ClassNode node = new ClassNode();
            new ClassReader(entry.getValue()).accept(node, 0);
            classes.put(node.name, node);
        }
        return classes;
    }

    private void buildClassMapping(ClassHierarchy hierarchy) {
        Map<String, ClassNode> classes = hierarchy.getClasses();
        StringBuilder prefixBuilder = new StringBuilder();
        for (String part : config.obfuscateClassPath.trim().split("\\.")) {
            if (!part.isEmpty()) {
                prefixBuilder.append(part).append('/');
            }
        }
        classPathPrefix = config.obfuscateClassPathEnabled ? prefixBuilder.toString() : "";
        for (ClassNode node : classes.values()) {
            for (InnerClassNode innerClass : node.innerClasses) {
                if (innerClass.outerName != null) {
                    outerNames.putIfAbsent(innerClass.name, innerClass.outerName);
                }
            }
        }
        for (String name : classes.keySet()) {
            mapClassName(name, classes);
        }
    }

    private String mapClassName(String name, Map<String, ClassNode> classes) {
        String existing = classMapping.get(name);
        if (existing != null) {
            return existing;
        }
        if (!config.renameClasses || name.equals("module-info") || NameUtility.simpleName(name).equals("package-info") || serviceProviders.contains(name) || isKeptClass(name)) {
            classMapping.put(name, name);
            allocatedClassNames.add(name);
            return name;
        }
        String outer = outerNames.get(name);
        String prefix;
        if (outer != null && !outer.equals(name) && classes.containsKey(outer)) {
            prefix = mapClassName(outer, classes) + "$";
        } else {
            String base = classPathPrefix.isEmpty() ? NameUtility.packageName(name) : classPathPrefix;
            prefix = config.splitSubPath ? base + subPathOf(name) : base;
        }
        String candidate;
        do {
            candidate = prefix + nameAllocator.nextClassName();
        } while (!allocatedClassNames.add(candidate));
        classMapping.put(name, candidate);
        return candidate;
    }

    private void buildMemberMapping(ClassHierarchy hierarchy) {
        Map<String, ClassNode> classes = hierarchy.getClasses();
        Set<String> usedNames = new HashSet<>();
        for (ClassNode node : classes.values()) {
            for (MethodNode method : node.methods) {
                usedNames.add(method.name);
            }
            for (FieldNode field : node.fields) {
                usedNames.add(field.name);
            }
        }
        for (String className : classes.keySet()) {
            usedNames.addAll(hierarchy.namesOf(className));
        }

        for (ClassNode node : classes.values()) {
            Map<String, String> anchors = new HashMap<>();
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(node.name);
            while (!queue.isEmpty()) {
                String currentName = queue.poll();
                if (!visited.add(currentName)) {
                    continue;
                }
                ClassNode current = classes.get(currentName);
                if (current == null) {
                    continue;
                }
                for (MethodNode method : current.methods) {
                    if (method.name.charAt(0) != '<') {
                        unionMember(anchors, method.name + method.desc, currentName + '#' + method.name + method.desc);
                    }
                }
                for (FieldNode field : current.fields) {
                    unionMember(anchors, field.name + field.desc, currentName + '#' + field.name + field.desc);
                }
                if (current.superName != null) {
                    queue.add(current.superName);
                }
                queue.addAll(current.interfaces);
            }
        }

        Set<String> pinnedGroups = new HashSet<>();
        for (ClassNode node : classes.values()) {
            Set<String> members = hierarchy.membersOf(node.name);
            boolean missing = hierarchy.missingOf(node.name);
            for (MethodNode method : node.methods) {
                if (method.name.charAt(0) != '<' && isPinnedMember(node, method.name, method.desc, method.access, members, missing)) {
                    pinnedGroups.add(find(node.name + '#' + method.name + method.desc));
                }
            }
            for (FieldNode field : node.fields) {
                if (isPinnedMember(node, field.name, field.desc, field.access, members, missing)) {
                    pinnedGroups.add(find(node.name + '#' + field.name + field.desc));
                }
            }
        }

        Map<String, String> groupNames = new HashMap<>();
        for (ClassNode node : classes.values()) {
            for (MethodNode method : node.methods) {
                if (method.name.charAt(0) == '<') {
                    continue;
                }
                String key = node.name + '#' + method.name + method.desc;
                String group = find(key);
                String newName = groupNames.get(group);
                if (newName == null) {
                    if (pinnedGroups.contains(group)) {
                        newName = method.name;
                    } else {
                        do {
                            newName = nameAllocator.nextMethodName();
                        } while (!usedNames.add(newName));
                    }
                    groupNames.put(group, newName);
                }
                methodMapping.put(key, newName);
            }
            for (FieldNode field : node.fields) {
                String key = node.name + '#' + field.name + field.desc;
                String group = find(key);
                String newName = groupNames.get(group);
                if (newName == null) {
                    if (pinnedGroups.contains(group)) {
                        newName = field.name;
                    } else {
                        do {
                            newName = nameAllocator.nextFieldName();
                        } while (!usedNames.add(newName));
                    }
                    groupNames.put(group, newName);
                }
                fieldMapping.put(key, newName);
            }
        }
    }

    private void unionMember(Map<String, String> anchors, String signature, String key) {
        memberGroups.putIfAbsent(key, key);
        String anchor = anchors.putIfAbsent(signature, key);
        if (anchor != null) {
            memberGroups.put(find(anchor), find(key));
        }
    }

    private String find(String key) {
        String parent = memberGroups.get(key);
        if (parent == null || parent.equals(key)) {
            return key;
        }
        String root = find(parent);
        memberGroups.put(key, root);
        return root;
    }

    private boolean isPinnedMember(ClassNode node, String name, String descriptor, int access, Set<String> externalMembers, boolean missingAncestor) {
        if (isKeptMember(node.name, name)) {
            return true;
        }
        Set<String> pinnedNames = reflectivePins.get(node.name);
        boolean pinnedByClass = (isMixinClass(node) || stringReferencedClasses.contains(node.name)) && !reflectiveRewrittenNames.contains(name);
        if (pinnedByClass || reflectiveNamePins.contains(name) || pinnedNames != null && pinnedNames.contains(name)) {
            return true;
        }
        if (name.charAt(0) == '<' || (access & Opcodes.ACC_NATIVE) != 0) {
            return true;
        }
        if (name.equals("serialVersionUID") || name.equals("$VALUES")) {
            return true;
        }
        if ((access & Opcodes.ACC_STATIC) != 0 && (name.equals("main") || name.equals("premain") || name.equals("agentmain"))) {
            return true;
        }
        if ((node.access & (Opcodes.ACC_RECORD | Opcodes.ACC_ANNOTATION)) != 0) {
            return true;
        }
        if ((node.access & Opcodes.ACC_ENUM) != 0 && (name.equals("values") || name.equals("valueOf"))) {
            return true;
        }
        if (name.equals("readObject") || name.equals("writeObject") || name.equals("readObjectNoData")
                || name.equals("readResolve") || name.equals("writeReplace")
                || name.equals("readExternal") || name.equals("writeExternal")) {
            return true;
        }
        if (externalMembers.contains(name + descriptor)) {
            return true;
        }
        return missingAncestor && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 && (access & Opcodes.ACC_STATIC) == 0;
    }

    private boolean isKeptClass(String internalName) {
        for (String rule : keptClassRules) {
            if (rule.endsWith("*") ? internalName.startsWith(rule.substring(0, rule.length() - 1)) : rule.equals(internalName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isKeptMember(String internalOwner, String memberName) {
        for (String rule : keptMemberRules) {
            int hash = rule.lastIndexOf('#');
            if (!rule.substring(hash + 1).equals(memberName)) {
                continue;
            }
            String owner = rule.substring(0, hash);
            if (owner.endsWith("*") ? internalOwner.startsWith(owner.substring(0, owner.length() - 1)) : owner.equals(internalOwner)) {
                return true;
            }
        }
        return false;
    }

    private void buildSubPathGroups(ClassHierarchy hierarchy) {
        Map<String, ClassNode> classes = hierarchy.getClasses();
        for (ClassNode node : classes.values()) {
            classGroups.putIfAbsent(node.name, node.name);
            for (InnerClassNode inner : node.innerClasses) {
                if (inner.name.equals(node.name) && inner.outerName != null && classes.containsKey(inner.outerName)) {
                    unionClass(node.name, inner.outerName);
                }
            }
        }
        for (ClassNode node : classes.values()) {
            for (String type : referencedTypes(node)) {
                ClassNode target = classes.get(type);
                if (target != null && (target.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0) {
                    unionClass(node.name, target.name);
                }
            }
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    boolean call = instruction instanceof MethodInsnNode;
                    if (!call && !(instruction instanceof FieldInsnNode)) {
                        continue;
                    }
                    String owner = call ? ((MethodInsnNode) instruction).owner : ((FieldInsnNode) instruction).owner;
                    if (!classes.containsKey(owner)) {
                        continue;
                    }
                    String name = call ? ((MethodInsnNode) instruction).name : ((FieldInsnNode) instruction).name;
                    String descriptor = call ? ((MethodInsnNode) instruction).desc : ((FieldInsnNode) instruction).desc;
                    int access = memberAccess(hierarchy, owner, name, descriptor, call);
                    if (access >= 0 && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) == 0) {
                        unionClass(node.name, owner);
                    }
                }
            }
        }
    }

    private int memberAccess(ClassHierarchy hierarchy, String owner, String name, String descriptor, boolean method) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            ClassNode node = hierarchy.getClasses().get(current);
            if (node == null) {
                continue;
            }
            if (method) {
                for (MethodNode candidate : node.methods) {
                    if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) {
                        return candidate.access;
                    }
                }
            } else {
                for (FieldNode candidate : node.fields) {
                    if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) {
                        return candidate.access;
                    }
                }
            }
            if (node.superName != null) {
                queue.add(node.superName);
            }
            queue.addAll(node.interfaces);
        }
        return -1;
    }

    private Set<String> referencedTypes(ClassNode node) {
        Set<String> types = new HashSet<>();
        if (node.superName != null) {
            types.add(node.superName);
        }
        types.addAll(node.interfaces);
        for (FieldNode field : node.fields) {
            addDescriptorTypes(types, field.desc);
        }
        for (MethodNode method : node.methods) {
            addDescriptorTypes(types, method.desc);
            if (method.exceptions != null) {
                types.addAll(method.exceptions);
            }
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof TypeInsnNode) {
                    types.add(((TypeInsnNode) instruction).desc);
                } else if (instruction instanceof FieldInsnNode) {
                    types.add(((FieldInsnNode) instruction).owner);
                    addDescriptorTypes(types, ((FieldInsnNode) instruction).desc);
                } else if (instruction instanceof MethodInsnNode) {
                    types.add(((MethodInsnNode) instruction).owner);
                    addDescriptorTypes(types, ((MethodInsnNode) instruction).desc);
                } else if (instruction instanceof InvokeDynamicInsnNode) {
                    addDescriptorTypes(types, ((InvokeDynamicInsnNode) instruction).desc);
                } else if (instruction instanceof MultiANewArrayInsnNode) {
                    addDescriptorTypes(types, ((MultiANewArrayInsnNode) instruction).desc);
                } else if (instruction instanceof LdcInsnNode && ((LdcInsnNode) instruction).cst instanceof Type) {
                    addType(types, (Type) ((LdcInsnNode) instruction).cst);
                }
            }
        }
        return types;
    }

    private void addDescriptorTypes(Set<String> types, String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) {
                addType(types, argument);
            }
            addType(types, type.getReturnType());
        } else {
            addType(types, type);
        }
    }

    private void addType(Set<String> types, Type type) {
        if (type.getSort() == Type.OBJECT) {
            types.add(type.getInternalName());
        } else if (type.getSort() == Type.ARRAY) {
            addType(types, type.getElementType());
        }
    }

    private void unionClass(String left, String right) {
        classGroups.putIfAbsent(left, left);
        classGroups.putIfAbsent(right, right);
        classGroups.put(findClass(left), findClass(right));
    }

    private String findClass(String key) {
        String parent = classGroups.get(key);
        if (parent == null || parent.equals(key)) {
            return key;
        }
        String root = findClass(parent);
        classGroups.put(key, root);
        return root;
    }

    private String subPathOf(String className) {
        return classGroupPaths.computeIfAbsent(findClass(className), key -> nameAllocator.nextPackagePath());
    }

    private void collectReflectiveStrings(ClassHierarchy hierarchy) {
        for (ClassNode node : hierarchy.getClasses().values()) {
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (!call.owner.equals("java/lang/Class")) {
                        continue;
                    }
                    boolean methodLookup = call.name.equals("getMethod") || call.name.equals("getDeclaredMethod");
                    if (!methodLookup && !call.name.equals("getField") && !call.name.equals("getDeclaredField")) {
                        continue;
                    }
                    LdcInsnNode nameNode = null;
                    String target = null;
                    int steps = 0;
                    for (AbstractInsnNode previous = call.getPrevious(); previous != null && steps < 40; previous = previous.getPrevious()) {
                        if (previous instanceof LabelNode || previous instanceof LineNumberNode || previous instanceof FrameNode) {
                            continue;
                        }
                        steps++;
                        if (previous instanceof VarInsnNode) {
                            break;
                        }
                        if (!(previous instanceof LdcInsnNode)) {
                            if (previous instanceof InsnNode || previous instanceof TypeInsnNode || previous instanceof IntInsnNode) {
                                continue;
                            }
                            break;
                        }
                        Object constant = ((LdcInsnNode) previous).cst;
                        if (constant instanceof String) {
                            if (nameNode == null) {
                                nameNode = (LdcInsnNode) previous;
                            }
                        } else if (constant instanceof Type && nameNode != null && target == null && ((Type) constant).getSort() == Type.OBJECT) {
                            target = ((Type) constant).getInternalName();
                        }
                    }
                    if (nameNode == null || ((String) nameNode.cst).isEmpty()) {
                        continue;
                    }
                    String name = (String) nameNode.cst;
                    if (target == null || !hierarchy.getClasses().containsKey(target)) {
                        reflectiveNamePins.add(name);
                        continue;
                    }
                    List<String> keys = memberKeys(hierarchy, target, name, methodLookup);
                    if (keys.size() == 1) {
                        ReflectiveString rewrite = new ReflectiveString(nameNode, keys.get(0), methodLookup);
                        reflectiveStrings.add(rewrite);
                        reflectiveRewrittenNames.add(name);
                    } else if (keys.size() > 1) {
                        for (String key : keys) {
                            reflectivePins.computeIfAbsent(key.substring(0, key.indexOf('#')), key2 -> new HashSet<>()).add(name);
                        }
                    }
                }
            }
        }
    }

    private void collectStringReferencedClasses(ClassHierarchy hierarchy) {
        for (ClassNode node : hierarchy.getClasses().values()) {
            for (FieldNode field : node.fields) {
                if (field.value instanceof String) {
                    addStringReference(hierarchy, (String) field.value);
                }
            }
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof LdcInsnNode)) {
                        continue;
                    }
                    Object constant = ((LdcInsnNode) instruction).cst;
                    if (constant instanceof String) {
                        addStringReference(hierarchy, (String) constant);
                    } else if (constant instanceof Type && ((Type) constant).getSort() == Type.OBJECT && buildsClassName(instruction)) {
                        stringReferencedClasses.add(((Type) constant).getInternalName());
                    }
                }
            }
        }
    }

    private boolean buildsClassName(AbstractInsnNode instruction) {
        int steps = 0;
        for (AbstractInsnNode next = instruction.getNext(); next != null && steps < 4; next = next.getNext(), steps++) {
            if (next instanceof LabelNode || next instanceof LineNumberNode || next instanceof FrameNode) {
                continue;
            }
            if (!(next instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) next;
            return call.owner.equals("java/lang/Class") && call.name.equals("getName")
                    || call.owner.equals("org/objectweb/asm/Type") && (call.name.equals("getDescriptor") || call.name.equals("getInternalName"));
        }
        return false;
    }

    private void addStringReference(ClassHierarchy hierarchy, String text) {
        if (hierarchy.getClasses().containsKey(text)) {
            stringReferencedClasses.add(text);
            return;
        }
        String internalName = text.replace('.', '/');
        if (hierarchy.getClasses().containsKey(internalName)) {
            stringReferencedClasses.add(internalName);
        }
    }

    private boolean isMixinClass(ClassNode node) {
        if (node.name.toLowerCase(Locale.ROOT).contains("mixin")) {
            return true;
        }
        for (List<AnnotationNode> annotations : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (annotations == null) {
                continue;
            }
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc != null && annotation.desc.toLowerCase(Locale.ROOT).contains("mixin")) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> memberKeys(ClassHierarchy hierarchy, String owner, String name, boolean method) {
        List<String> keys = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            ClassNode node = hierarchy.getClasses().get(current);
            if (node == null) {
                continue;
            }
            if (method) {
                for (MethodNode candidate : node.methods) {
                    if (candidate.name.equals(name)) {
                        keys.add(current + '#' + name + candidate.desc);
                    }
                }
            } else {
                for (FieldNode candidate : node.fields) {
                    if (candidate.name.equals(name)) {
                        keys.add(current + '#' + name + candidate.desc);
                    }
                }
            }
            if (node.superName != null) {
                queue.add(node.superName);
            }
            queue.addAll(node.interfaces);
        }
        return keys;
    }

    private void applyReflectiveStrings() {
        for (ReflectiveString reflective : reflectiveStrings) {
            String mapped = (reflective.method ? methodMapping : fieldMapping).get(reflective.key);
            if (mapped != null) {
                reflective.node.cst = mapped;
            }
        }
    }

    private static class ReflectiveString {

        private final LdcInsnNode node;
        private final String key;
        private final boolean method;

        private ReflectiveString(LdcInsnNode node, String key, boolean method) {
            this.node = node;
            this.key = key;
            this.method = method;
        }
    }

    private void writeJar(Map<String, byte[]> entries, Map<String, ClassNode> classes, NameRemapper remapper, ClassLoader classLoader, File outputFile) throws IOException {
        Map<String, ClassNode> remappedClasses = new LinkedHashMap<>();
        Map<String, ClassNode> originalToRemapped = new HashMap<>();
        Map<String, String> originalNames = new HashMap<>();
        frameClassLoader = classLoader;
        frameClasses = remappedClasses;
        commonSuperCache.clear();
        for (ClassNode node : classes.values()) {
            ClassNode remapped = remapClass(node, remapper);
            remappedClasses.put(remapped.name, remapped);
            originalToRemapped.put(node.name, remapped);
            originalNames.put(remapped.name, node.name);
        }
        List<ClassNode> generatedClasses = new ArrayList<>();
        if (config.splitMethods) {
            MethodSplitter splitter = new MethodSplitter(config, classes, remappedClasses, classMapping, originalNames, classLoader, nameAllocator, allocatedClassNames);
            splitter.splitAll();
            generatedClasses.addAll(splitter.getGeneratedClasses());
            for (ClassNode generated : generatedClasses) {
                frameClasses.put(generated.name, generated);
            }
            logger.accept("方法拆分 " + splitter.getSplitMethodCount() + " 个方法, 生成类 " + generatedClasses.size() + " 个");
        }
        Manifest manifest = JarUtility.readManifest(entries);
        if (manifest != null) {
            JarUtility.remapManifest(manifest, classMapping);
            String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass != null && !remappedClasses.containsKey(NameUtility.internalName(mainClass.trim())) && classes.containsKey(NameUtility.internalName(mainClass.trim()))) {
                logger.accept("警告: Main-Class " + mainClass + " 与输出类名不一致");
            }
        }
        int processed = 0;
        int lastProgress = 45;
        try (OutputStream fileStream = new BufferedOutputStream(new FileOutputStream(outputFile));
             JarOutputStream jarStream = manifest == null ? new JarOutputStream(fileStream) : new JarOutputStream(fileStream, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String name = entry.getKey();
                byte[] data = entry.getValue();
                processed++;
                int percent = 45 + processed * 50 / entries.size();
                if (percent != lastProgress) {
                    lastProgress = percent;
                    progress.accept(percent);
                }
                if (JarUtility.isMetadataEntry(name)) {
                    continue;
                }
                if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassNode node = originalToRemapped.get(reader.getClassName());
                    if (node == null) {
                        ClassNode versioned = new ClassNode();
                        reader.accept(versioned, 0);
                        node = remapClass(versioned, remapper);
                    }
                    String suffix = reader.getClassName() + ".class";
                    name = (name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : "") + node.name + ".class";
                    data = writeClass(node);
                } else if (config.renameClasses && name.startsWith("META-INF/services/")) {
                    String serviceName = name.substring("META-INF/services/".length());
                    String mappedService = classMapping.getOrDefault(NameUtility.internalName(serviceName), NameUtility.internalName(serviceName));
                    name = "META-INF/services/" + NameUtility.binaryName(mappedService);
                    data = remapServiceFile(data);
                }
                JarEntry jarEntry = new JarEntry(name);
                jarStream.putNextEntry(jarEntry);
                jarStream.write(data);
                jarStream.closeEntry();
            }
            for (ClassNode generated : generatedClasses) {
                JarEntry jarEntry = new JarEntry(generated.name + ".class");
                jarStream.putNextEntry(jarEntry);
                jarStream.write(writeClass(generated));
                jarStream.closeEntry();
            }
        }
    }

    private ClassNode remapClass(ClassNode node, NameRemapper remapper) {
        ClassNode remapped = new ClassNode();
        node.accept(new ClassRemapper(remapped, remapper));
        if (config.stripDebugInfo) {
            stripDebugInfo(remapped);
        }
        return remapped;
    }

    private byte[] writeClass(ClassNode node) {
        if (config.stripSignatures) {
            stripSignatures(node);
        }
        boolean rebuildFrames = config.rebuildStackMapFrames && (node.version & 0xFFFF) >= Opcodes.V1_6;
        if (rebuildFrames) {
            stripStackMapFrames(node);
            frameClasses.putIfAbsent(node.name, node);
        }
        if (config.encryptStrings || config.adaptClassStrings || !stringReplacements.isEmpty()) {
            new StringRewriter(config, node, classMapping, stringReplacements, nameAllocator).rewrite();
        }
        ClassWriter writer = rebuildFrames ? new FrameClassWriter(frameClassLoader, frameClasses, commonSuperCache) : new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private void stripDebugInfo(ClassNode node) {
        node.sourceFile = null;
        node.sourceDebug = null;
        for (MethodNode method : node.methods) {
            method.localVariables = null;
            method.parameters = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof LineNumberNode) {
                    method.instructions.remove(instruction);
                }
                instruction = next;
            }
        }
    }

    private void stripSignatures(ClassNode node) {
        node.signature = null;
        for (FieldNode field : node.fields) {
            field.signature = null;
        }
        for (MethodNode method : node.methods) {
            method.signature = null;
            if (method.localVariables == null) {
                continue;
            }
            for (LocalVariableNode variable : method.localVariables) {
                variable.signature = null;
            }
        }
        if (node.recordComponents == null) {
            return;
        }
        for (RecordComponentNode component : node.recordComponents) {
            component.signature = null;
        }
    }

    private void stripStackMapFrames(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof FrameNode) {
                    method.instructions.remove(instruction);
                }
                instruction = next;
            }
        }
    }

    private byte[] remapServiceFile(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                builder.append('\n');
            }
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                builder.append(line);
                continue;
            }
            String mapped = classMapping.get(NameUtility.internalName(trimmed));
            builder.append(mapped == null ? line : line.replace(trimmed, NameUtility.binaryName(mapped)));
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
