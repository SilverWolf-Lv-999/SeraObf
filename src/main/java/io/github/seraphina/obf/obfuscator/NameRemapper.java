package io.github.seraphina.obf.obfuscator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

import java.util.List;
import java.util.Map;

public class NameRemapper extends Remapper {

    private final Map<String, String> classMapping;
    private final Map<String, String> methodMapping;
    private final Map<String, String> fieldMapping;
    private final ClassHierarchy hierarchy;

    public NameRemapper(Map<String, String> classMapping, Map<String, String> methodMapping, Map<String, String> fieldMapping, ClassHierarchy hierarchy) {
        super(Opcodes.ASM9);
        this.classMapping = classMapping;
        this.methodMapping = methodMapping;
        this.fieldMapping = fieldMapping;
        this.hierarchy = hierarchy;
    }

    @Override
    public String map(String internalName) {
        return classMapping.getOrDefault(internalName, internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (name.charAt(0) == '<') {
            return name;
        }
        String mapped = resolve(methodMapping, owner, name + descriptor);
        return mapped == null ? name : mapped;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        String mapped = resolve(fieldMapping, owner, name + descriptor);
        return mapped == null ? name : mapped;
    }

    private String resolve(Map<String, String> mapping, String owner, String signature) {
        if (owner == null) {
            return null;
        }
        String mapped = mapping.get(owner + '#' + signature);
        if (mapped != null) {
            return mapped;
        }
        List<String> parentNames = hierarchy.parentsOf(owner);
        if (parentNames != null) {
            for (String parent : parentNames) {
                String result = resolve(mapping, parent, signature);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
