package io.github.seraphina.obf.utility;

public final class NameUtility {

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
}
