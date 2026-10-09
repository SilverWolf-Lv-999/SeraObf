package io.github.seraphina.obf.window;

import io.github.seraphina.obf.common.config.ClientConfig;
import io.github.seraphina.obf.obfuscator.Obfuscator;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.function.Consumer;

public class ObfuscateWorker extends SwingWorker<Void, String> {

    private final ClientConfig config;
    private final Consumer<String> logSink;

    public ObfuscateWorker(ClientConfig config, Consumer<String> logSink) {
        this.config = config;
        this.logSink = logSink;
    }

    @Override
    protected Void doInBackground() throws Exception {
        new Obfuscator(config, this::publish, this::setProgress).obfuscate();
        return null;
    }

    @Override
    protected void process(List<String> chunks) {
        for (String message : chunks) {
            logSink.accept(message);
        }
    }
}
