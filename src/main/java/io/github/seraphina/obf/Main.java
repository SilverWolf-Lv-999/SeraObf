package io.github.seraphina.obf;

import io.github.seraphina.obf.common.config.ClientConfig;
import io.github.seraphina.obf.obfuscator.Obfuscator;
import io.github.seraphina.obf.window.MainWindow;

import javax.swing.UIManager;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length >= 2) {
            ClientConfig config = ClientConfig.INSTANCE;
            config.inputJar = args[0];
            config.outputJar = args[1];
            if (args.length >= 3) {
                config.libraryJars = args[2];
            }
            if (args.length >= 4) {
                config.obfuscateClassPath = args[3];
            }
            if (args.length >= 5) {
                config.keepRules = args[4];
            }
            if (args.length >= 6) {
                config.stringReplacements = args[5];
            }
            new Obfuscator(config, System.out::println, value -> {
            }).obfuscate();
            return;
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        new MainWindow().setVisible(true);
    }
}
