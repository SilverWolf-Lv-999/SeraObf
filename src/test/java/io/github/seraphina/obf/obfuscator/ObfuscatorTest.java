package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.common.config.ClientConfig;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObfuscatorTest {

    private static final String SAMPLE_SOURCE = String.join("\n",
            "package demo;",
            "",
            "import java.util.function.Supplier;",
            "",
            "public class Sample implements Runnable, Greeter {",
            "    private final String value = \"hidden-value\";",
            "",
            "    @Override",
            "    public void run() {",
            "    }",
            "",
            "    @Override",
            "    public String greet() {",
            "        return Greeter.PREFIX + \"-\" + value();",
            "    }",
            "",
            "    public String value() {",
            "        return value;",
            "    }",
            "",
            "    public Supplier<String> supplier() {",
            "        return () -> \"lambda-value\";",
            "    }",
            "",
            "    public static String nested() {",
            "        return new Nested().text();",
            "    }",
            "",
            "    public static String selfName() {",
            "        try {",
            "            return Class.forName(\"demo.Sample\").getName();",
            "        } catch (ClassNotFoundException exception) {",
            "            return \"missing\";",
            "        }",
            "    }",
            "",
            "    public static int mixed(boolean flag, byte low, char letter, short count) {",
            "        int result = low;",
            "        result += letter;",
            "        result += count;",
            "        result += Boolean.compare(flag, false);",
            "        result += 1;",
            "        result += 2;",
            "        return result;",
            "    }",
            "",
            "    public static String reflectName() {",
            "        try {",
            "            return Nested.class.getDeclaredMethod(\"text\").getName();",
            "        } catch (ReflectiveOperationException exception) {",
            "            return \"missing\";",
            "        }",
            "    }",
            "",
            "    public static void main(String[] args) {",
            "        Sample sample = new Sample();",
            "        System.out.println(sample.value());",
            "        System.out.println(sample.supplier().get());",
            "        System.out.println(sample.greet());",
            "        System.out.println(nested());",
            "        System.out.println(Kind.FIRST);",
            "        System.out.println(selfName());",
            "        System.out.println(\"mixed=\" + mixed(true, (byte) 1, 'A', (short) 2));",
            "        System.out.println(\"reflect=\" + reflectName());",
            "    }",
            "",
            "    static class Nested {",
            "        String text() {",
            "            return \"nested-value\";",
            "        }",
            "    }",
            "",
            "    enum Kind {",
            "        FIRST, SECOND",
            "    }",
            "}",
            "");

    private static final String GREETER_SOURCE = String.join("\n",
            "package demo;",
            "",
            "public interface Greeter {",
            "    String PREFIX = Greeter.prefixValue();",
            "",
            "    static String prefixValue() {",
            "        return \"prefix-value\";",
            "    }",
            "",
            "    String greet();",
            "}",
            "");

    private static final String LAUNCHER_SOURCE = String.join("\n",
            "package demo;",
            "",
            "import java.net.URL;",
            "import java.util.jar.JarInputStream;",
            "import java.util.jar.Manifest;",
            "",
            "public class Launcher {",
            "    public static void main(String[] args) throws Exception {",
            "        URL location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();",
            "        Manifest manifest = new JarInputStream(location.openStream()).getManifest();",
            "        Class<?> type = Class.forName(manifest.getMainAttributes().getValue(\"Rsrc-Main-Class\"));",
            "        type.getMethod(\"main\", String[].class).invoke(null, (Object) args);",
            "    }",
            "}",
            "");

    private static final String GREETER_PROVIDER_SOURCE = String.join("\n",
            "package demo;",
            "",
            "public class GreeterProvider implements Greeter {",
            "    @Override",
            "    public String greet() {",
            "        return \"provider\";",
            "    }",
            "}",
            "");

    @Test
    void obfuscatesJarEndToEnd() throws Exception {
        Path workDir = Files.createTempDirectory("seraobf");
        Path inputJar = compileSample(workDir);

        Path outputJar = workDir.resolve("output.jar");
        String mainClass = obfuscate(inputJar, outputJar, "obf.myapp.random", true, true, true, "", "", true);
        assertTrue(mainClass.startsWith("obf.myapp.random."), mainClass);
        assertTrue(countClasses(outputJar) > countClasses(inputJar));
        String rsrcMainClass;
        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(mainClass.replace('.', '/') + ".class");
            String classText;
            try (InputStream stream = jarFile.getInputStream(entry)) {
                classText = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            rsrcMainClass = jarFile.getManifest().getMainAttributes().getValue("Rsrc-Main-Class");
            assertTrue(rsrcMainClass.startsWith("obf.myapp.random."), rsrcMainClass);
            assertNotEquals(mainClass, rsrcMainClass);
            assertNotNull(jarFile.getJarEntry(rsrcMainClass.replace('.', '/') + ".class"));
            assertFalse(classText.contains("hidden-value"));
            assertFalse(classText.contains("lambda-value"));
        }
        String processOutput = runJar(outputJar);
        assertTrue(processOutput.contains("prefix-value-hidden-value"), processOutput);
        assertTrue(processOutput.contains("lambda-value"), processOutput);
        assertTrue(processOutput.contains("nested-value"), processOutput);
        assertTrue(processOutput.contains("FIRST"), processOutput);
        assertTrue(processOutput.contains("mixed=72"), processOutput);
        assertFalse(processOutput.contains("reflect=missing"), processOutput);
        assertFalse(processOutput.contains("reflect=text"), processOutput);
        assertTrue(processOutput.contains(rsrcMainClass), processOutput);
        String[] serviceEntry = readServiceEntry(outputJar);
        assertEquals("META-INF/services/demo.Greeter", serviceEntry[0]);
        assertEquals("demo.GreeterProvider", serviceEntry[1]);

        Path keptJar = workDir.resolve("kept.jar");
        String keptMainClass = obfuscate(inputJar, keptJar, "", true, true, true, "", "", true);
        assertTrue(keptMainClass.startsWith("demo."), keptMainClass);
        assertTrue(runJar(keptJar).contains("prefix-value-hidden-value"));

        Path plainJar = workDir.resolve("plain.jar");
        obfuscate(inputJar, plainJar, "obf.myapp.random", false, true, true, "", "", true);
        assertEquals(countClasses(inputJar), countClasses(plainJar));

        Path inlineJar = workDir.resolve("inline.jar");
        String inlineMainClass = obfuscate(inputJar, inlineJar, "obf.myapp.random", true, false, false, "", "", true);
        assertEquals(countClasses(inputJar), countClasses(inlineJar));
        try (JarFile jarFile = new JarFile(inlineJar.toFile())) {
            byte[] classBytes;
            try (InputStream stream = jarFile.getInputStream(jarFile.getJarEntry(inlineMainClass.replace('.', '/') + ".class"))) {
                classBytes = stream.readAllBytes();
            }
            assertTrue(new String(classBytes, StandardCharsets.ISO_8859_1).contains("$split1"));
        }
        assertTrue(runJar(inlineJar).contains("prefix-value-hidden-value"));

        Path replacedJar = workDir.resolve("replaced.jar");
        obfuscate(inputJar, replacedJar, "", true, true, true, "", "hidden-value=patched-value", true);
        assertTrue(runJar(replacedJar).contains("patched-value"));

        Path serviceJar = workDir.resolve("service.jar");
        obfuscate(inputJar, serviceJar, "", true, true, true, "", "", false);
        String[] remappedService = readServiceEntry(serviceJar);
        assertNotEquals("META-INF/services/demo.Greeter", remappedService[0]);
        assertNotEquals("demo.GreeterProvider", remappedService[1]);
        try (JarFile jarFile = new JarFile(serviceJar.toFile())) {
            assertNotNull(jarFile.getJarEntry(remappedService[1].replace('.', '/') + ".class"));
        }
        assertTrue(runJar(serviceJar).contains("prefix-value-hidden-value"));
    }

    private static Path compileSample(Path workDir) throws Exception {
        Path sourceFile = workDir.resolve("src/demo/Sample.java");
        Path greeterFile = workDir.resolve("src/demo/Greeter.java");
        Path launcherFile = workDir.resolve("src/demo/Launcher.java");
        Path providerFile = workDir.resolve("src/demo/GreeterProvider.java");
        Path classDir = workDir.resolve("classes");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classDir);
        Files.writeString(sourceFile, SAMPLE_SOURCE);
        Files.writeString(greeterFile, GREETER_SOURCE);
        Files.writeString(launcherFile, LAUNCHER_SOURCE);
        Files.writeString(providerFile, GREETER_PROVIDER_SOURCE);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classDir.toString(), sourceFile.toString(), greeterFile.toString(), launcherFile.toString(), providerFile.toString()));
        Path jarPath = workDir.resolve("input.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "demo.Launcher ");
        manifest.getMainAttributes().putValue("Rsrc-Main-Class", "demo.Sample");
        try (JarOutputStream jarStream = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jarStream.putNextEntry(new JarEntry("META-INF/services/demo.Greeter"));
            jarStream.write("demo.GreeterProvider\n".getBytes(StandardCharsets.UTF_8));
            jarStream.closeEntry();
            writeEntries(jarStream, classDir.toFile(), "");
        }
        return jarPath;
    }

    private static String obfuscate(Path inputJar, Path outputJar, String obfuscateClassPath, boolean splitMethods, boolean randomSplitClass, boolean randomSplitMethodName, String keepRules, String stringReplacements, boolean keepServiceNames) throws Exception {
        ClientConfig config = ClientConfig.INSTANCE;
        config.inputJar = inputJar.toString();
        config.outputJar = outputJar.toString();
        config.libraryJars = "";
        config.obfuscateClassPath = obfuscateClassPath;
        config.renameClasses = true;
        config.renameMembers = true;
        config.encryptStrings = true;
        config.stripDebugInfo = true;
        config.splitMethods = splitMethods;
        config.randomSplitClass = randomSplitClass;
        config.randomSplitMethodName = randomSplitMethodName;
        config.keepRules = keepRules;
        config.stringReplacements = stringReplacements;
        config.keepServiceNames = keepServiceNames;
        new Obfuscator(config, message -> {
        }, value -> {
        }).obfuscate();
        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            return jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        }
    }

    private static String[] readServiceEntry(Path jarPath) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("META-INF/services/") || entry.isDirectory()) {
                    continue;
                }
                try (InputStream stream = jarFile.getInputStream(entry)) {
                    return new String[]{entry.getName(), new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim()};
                }
            }
        }
        return null;
    }

    private static int countClasses(Path jarPath) throws Exception {
        int count = 0;
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().endsWith(".class")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String runJar(Path jarPath) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jarPath.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), output);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static void writeEntries(JarOutputStream jarStream, File directory, String prefix) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = prefix + file.getName();
            if (file.isDirectory()) {
                writeEntries(jarStream, file, name + "/");
                continue;
            }
            jarStream.putNextEntry(new JarEntry(name));
            jarStream.write(Files.readAllBytes(file.toPath()));
            jarStream.closeEntry();
        }
    }
}
