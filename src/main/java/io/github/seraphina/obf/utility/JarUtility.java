package io.github.seraphina.obf.utility;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class JarUtility {

    public static Map<String, byte[]> readEntries(File file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> enumeration = jarFile.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream stream = jarFile.getInputStream(entry)) {
                    entries.put(entry.getName(), stream.readAllBytes());
                }
            }
        }
        return entries;
    }

    public static Manifest readManifest(Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(JarFile.MANIFEST_NAME)) {
                return new Manifest(new ByteArrayInputStream(entry.getValue()));
            }
        }
        return null;
    }

    public static void remapManifest(Manifest manifest, Map<String, String> classMapping) {
        Attributes attributes = manifest.getMainAttributes();
        for (String attribute : new String[]{"Main-Class", "Rsrc-Main-Class", "Start-Class", "JavaFX-Application-Class", "Premain-Class", "Agent-Class", "Launcher-Agent-Class"}) {
            String value = attributes.getValue(attribute);
            if (value == null) {
                continue;
            }
            String name = value.trim();
            String mapped = classMapping.get(NameUtility.internalName(name));
            attributes.putValue(attribute, NameUtility.binaryName(mapped == null ? name : mapped));
        }
    }

    public static boolean isMetadataEntry(String name) {
        if (name.equalsIgnoreCase(JarFile.MANIFEST_NAME) || name.equals("META-INF/INDEX.LIST")) {
            return true;
        }
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        String upperName = name.toUpperCase(Locale.ROOT);
        return upperName.endsWith(".SF")
                || upperName.endsWith(".RSA")
                || upperName.endsWith(".DSA")
                || upperName.endsWith(".EC")
                || upperName.endsWith(".SIG");
    }
}
