package io.github.seraphina.obf.utility;

import io.github.seraphina.obf.common.config.CommonConfig;

import java.util.Map;
import java.util.Set;

public final class NameUtility {

    public static String nextName(int index) {
        String firstCharacters = CommonConfig.NAME_FIRST_CHARACTERS;
        String characters = CommonConfig.NAME_CHARACTERS;
        StringBuilder builder = new StringBuilder();
        builder.append(firstCharacters.charAt(index % firstCharacters.length()));
        int value = index / firstCharacters.length();
        while (value > 0) {
            value--;
            builder.append(characters.charAt(value % characters.length()));
            value /= characters.length();
        }
        return builder.toString();
    }

    public static String packageName(String internalName) {
        int index = internalName.lastIndexOf('/');
        return index < 0 ? "" : internalName.substring(0, index + 1);
    }

    public static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    public static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    public static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    public static String allocate(String prefix, Map<String, Integer> counters, Set<String> allocated) {
        int index = counters.getOrDefault(prefix, 0);
        String candidate;
        do {
            candidate = prefix + nextName(index++);
        } while (!allocated.add(candidate));
        counters.put(prefix, index);
        return candidate;
    }
}
