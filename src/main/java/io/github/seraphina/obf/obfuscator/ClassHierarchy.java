package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.utility.NameUtility;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassHierarchy {

    private final Map<String, ClassNode> classes;
    private final ClassLoader classLoader;
    private final Map<String, List<String>> parents = new HashMap<>();
    private final Map<String, Set<String>> ancestorsMembers = new HashMap<>();
    private final Map<String, Set<String>> ancestorsNames = new HashMap<>();
    private final Map<String, Boolean> ancestorsMissing = new HashMap<>();
    private final Map<String, Class<?>> loadedTypes = new HashMap<>();
    private final Map<String, Set<String>> loadedMembers = new HashMap<>();
    private final Map<String, Set<String>> loadedNames = new HashMap<>();
    private final Set<String> failedTypes = new HashSet<>();

    public ClassHierarchy(Map<String, ClassNode> classes, ClassLoader classLoader) {
        this.classes = classes;
        this.classLoader = classLoader;
        for (ClassNode node : classes.values()) {
            List<String> parentNames = new ArrayList<>();
            if (node.superName != null) {
                parentNames.add(node.superName);
            }
            parentNames.addAll(node.interfaces);
            parents.put(node.name, parentNames);
        }
    }

    public Map<String, ClassNode> getClasses() {
        return classes;
    }

    public List<String> parentsOf(String name) {
        return parents.get(name);
    }

    public Set<String> membersOf(String className) {
        return ancestorsMembers.computeIfAbsent(className, this::collectAncestors);
    }

    public Set<String> namesOf(String className) {
        membersOf(className);
        return ancestorsNames.get(className);
    }

    public boolean missingOf(String className) {
        membersOf(className);
        return ancestorsMissing.getOrDefault(className, false);
    }

    private Set<String> collectAncestors(String className) {
        Set<String> members = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(parents.getOrDefault(className, Collections.emptyList()));
        boolean missing = false;
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!visited.add(name)) {
                continue;
            }
            ClassNode node = classes.get(name);
            if (node != null) {
                if (node.superName != null) {
                    queue.add(node.superName);
                }
                queue.addAll(node.interfaces);
                continue;
            }
            loadType(name);
            if (failedTypes.contains(name)) {
                missing = true;
                continue;
            }
            Class<?> type = loadedTypes.get(name);
            members.addAll(loadedMembers.get(name));
            names.addAll(loadedNames.get(name));
            if (type.getSuperclass() != null) {
                queue.add(NameUtility.internalName(type.getSuperclass().getName()));
            }
            for (Class<?> interfaceType : type.getInterfaces()) {
                queue.add(NameUtility.internalName(interfaceType.getName()));
            }
        }
        ancestorsNames.put(className, names);
        ancestorsMissing.put(className, missing);
        return members;
    }

    private void loadType(String internalName) {
        if (loadedTypes.containsKey(internalName) || failedTypes.contains(internalName)) {
            return;
        }
        Set<String> members = new HashSet<>();
        Set<String> names = new HashSet<>();
        Class<?> type;
        try {
            type = Class.forName(NameUtility.binaryName(internalName), false, classLoader);
            for (Method method : type.getDeclaredMethods()) {
                members.add(method.getName() + Type.getMethodDescriptor(method));
                names.add(method.getName());
            }
            for (Field field : type.getDeclaredFields()) {
                members.add(field.getName() + Type.getDescriptor(field.getType()));
                names.add(field.getName());
            }
        } catch (Throwable throwable) {
            failedTypes.add(internalName);
            return;
        }
        loadedTypes.put(internalName, type);
        loadedMembers.put(internalName, members);
        loadedNames.put(internalName, names);
    }
}
