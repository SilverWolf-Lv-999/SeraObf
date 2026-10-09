package io.github.seraphina.obf.common.config;

public final class ClientConfig {

    public static final ClientConfig INSTANCE = new ClientConfig();

    public String inputJar = "";

    public String outputJar = "";

    public String libraryJars = "";

    public String obfuscateClassPath = "obf.myapp.random";

    public boolean obfuscateClassPathEnabled = true;

    public boolean splitSubPath = false;

    public boolean renameClasses = true;

    public boolean renameMembers = true;

    public long dictionarySeed = 0L;

    public boolean encryptStrings = true;

    public boolean stripDebugInfo = true;

    public boolean stripSignatures = true;

    public boolean rebuildStackMapFrames = true;

    public boolean splitMethods = true;

    public boolean randomSplitClass = true;

    public boolean randomSplitMethodName = true;

    public boolean adaptClassStrings = true;

    public String keepRules = "";

    public String stringReplacements = "";

    public boolean keepServiceNames = true;

    private ClientConfig() {
    }
}
