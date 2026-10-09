package io.github.seraphina.obf.obfuscator;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FrameClassWriter extends ClassWriter {

    private final ClassLoader classLoader;
    private final Map<String, ClassNode> classes;
    private final Map<String, String> cache;

    public FrameClassWriter(ClassLoader classLoader, Map<String, ClassNode> classes, Map<String, String> cache) {
        super(COMPUTE_FRAMES);
        this.classLoader = classLoader;
        this.classes = classes;
        this.cache = cache;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        String key = type1 + ' ' + type2;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String result = commonSuper(type1, type2);
        cache.put(key, result);
        return result;
    }

    private String commonSuper(String type1, String type2) {
        if (isAssignable(type1, type2)) {
            return type1;
        }
        if (isAssignable(type2, type1)) {
            return type2;
        }
        if (isInterface(type1) || isInterface(type2)) {
            return "java/lang/Object";
        }
        String current = parentOf(type1);
        while (current != null && !isAssignable(current, type2)) {
            current = parentOf(current);
        }
        return current == null ? "java/lang/Object" : current;
    }

    private boolean isAssignable(String candidate, String type) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(candidate)) {
                return true;
            }
            ClassNode node = classes.get(current);
            if (node != null) {
                if (node.superName != null) {
                    queue.add(node.superName);
                }
                queue.addAll(node.interfaces);
                continue;
            }
            Class<?> loaded = load(current);
            if (loaded == null) {
                continue;
            }
            if (loaded.getSuperclass() != null) {
                queue.add(loaded.getSuperclass().getName().replace('.', '/'));
            }
            for (Class<?> parent : loaded.getInterfaces()) {
                queue.add(parent.getName().replace('.', '/'));
            }
        }
        return false;
    }

    private boolean isInterface(String type) {
        ClassNode node = classes.get(type);
        if (node != null) {
            return (node.access & Opcodes.ACC_INTERFACE) != 0;
        }
        Class<?> loaded = load(type);
        return loaded != null && loaded.isInterface();
    }

    private String parentOf(String type) {
        ClassNode node = classes.get(type);
        if (node != null) {
            return node.superName;
        }
        Class<?> loaded = load(type);
        if (loaded == null || loaded.getSuperclass() == null) {
            return null;
        }
        return loaded.getSuperclass().getName().replace('.', '/');
    }

    private Class<?> load(String internalName) {
        if (internalName.startsWith("[")) {
            return null;
        }
        try {
            return Class.forName(internalName.replace('/', '.'), false, classLoader);
        } catch (Throwable throwable) {
            return null;
        }
    }
}
