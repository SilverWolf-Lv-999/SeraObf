package io.github.seraphina.obf.window;

import io.github.seraphina.obf.common.config.ClientConfig;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;

public class MainWindow extends JFrame {

    private final ClientConfig config = ClientConfig.INSTANCE;
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JTextField libraryField = new JTextField();
    private final JTextField classPathField = new JTextField(config.obfuscateClassPath);
    private final JTextField keepField = new JTextField();
    private final JTextField replaceField = new JTextField();
    private final JCheckBox classBox = new JCheckBox("重命名类名", true);
    private final JCheckBox memberBox = new JCheckBox("重命名方法/字段", true);
    private final JCheckBox stringBox = new JCheckBox("字符串加密", true);
    private final JCheckBox debugBox = new JCheckBox("移除调试信息", true);
    private final JCheckBox splitBox = new JCheckBox("方法拆分", true);
    private final JCheckBox randomClassBox = new JCheckBox("随机类", true);
    private final JCheckBox randomMethodBox = new JCheckBox("随机方法名", true);
    private final JTextArea logArea = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("就绪");
    private final JButton inputButton = new JButton("选择");
    private final JButton outputButton = new JButton("选择");
    private final JButton libraryButton = new JButton("选择");
    private final JButton startButton = new JButton("开始混淆");

    public MainWindow() {
        super("SeraObf");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(960, 660);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(null);
        setContentPane(createContent());
    }

    private JPanel createContent() {
        JPanel filesPanel = new JPanel(new GridBagLayout());
        filesPanel.setBorder(new TitledBorder("文件"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        String[] labels = {"输入 Jar", "输出 Jar", "依赖 Classpath"};
        JTextField[] fields = {inputField, outputField, libraryField};
        JButton[] buttons = {inputButton, outputButton, libraryButton};
        for (int row = 0; row < fields.length; row++) {
            constraints.gridx = 0;
            constraints.gridy = row;
            constraints.weightx = 0;
            filesPanel.add(new JLabel(labels[row]), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            filesPanel.add(fields[row], constraints);
            constraints.gridx = 2;
            constraints.weightx = 0;
            filesPanel.add(buttons[row], constraints);
        }
        constraints.gridx = 0;
        constraints.gridy = fields.length;
        constraints.weightx = 0;
        filesPanel.add(new JLabel("混淆类路径"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        filesPanel.add(classPathField, constraints);
        constraints.gridx = 0;
        constraints.gridy = fields.length + 1;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        filesPanel.add(new JLabel("保留规则"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        filesPanel.add(keepField, constraints);
        constraints.gridx = 0;
        constraints.gridy = fields.length + 2;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        filesPanel.add(new JLabel("字符串替换"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        filesPanel.add(replaceField, constraints);
        constraints.gridwidth = 1;
        inputButton.addActionListener(event -> selectInputJar());
        outputButton.addActionListener(event -> selectOutputJar());
        libraryButton.addActionListener(event -> selectLibraryJars());

        JPanel optionPanel = new JPanel(new GridLayout(2, 4, 4, 4));
        optionPanel.setBorder(new TitledBorder("混淆选项"));
        optionPanel.add(classBox);
        optionPanel.add(memberBox);
        optionPanel.add(stringBox);
        optionPanel.add(debugBox);
        optionPanel.add(splitBox);
        optionPanel.add(randomClassBox);
        optionPanel.add(randomMethodBox);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new TitledBorder("运行日志"));

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.add(optionPanel, BorderLayout.NORTH);
        centerPanel.add(logScroll, BorderLayout.CENTER);

        progressBar.setStringPainted(true);
        startButton.addActionListener(event -> startObfuscation());
        getRootPane().setDefaultButton(startButton);
        JPanel actionPanel = new JPanel(new BorderLayout(8, 0));
        actionPanel.add(progressBar, BorderLayout.CENTER);
        actionPanel.add(startButton, BorderLayout.EAST);
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 6));
        bottomPanel.add(actionPanel, BorderLayout.NORTH);
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        content.add(filesPanel, BorderLayout.NORTH);
        content.add(centerPanel, BorderLayout.CENTER);
        content.add(bottomPanel, BorderLayout.SOUTH);
        return content;
    }

    private JFileChooser createChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Jar 文件", "jar"));
        return chooser;
    }

    private void selectInputJar() {
        JFileChooser chooser = createChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        inputField.setText(file.getAbsolutePath());
        if (outputField.getText().isBlank()) {
            String name = file.getName();
            String base = name.toLowerCase().endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
            outputField.setText(new File(file.getParentFile(), base + "-obf.jar").getAbsolutePath());
        }
    }

    private void selectOutputJar() {
        JFileChooser chooser = createChooser();
        if (!outputField.getText().isBlank()) {
            chooser.setSelectedFile(new File(outputField.getText().trim()));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".jar")) {
            file = new File(file.getAbsolutePath() + ".jar");
        }
        outputField.setText(file.getAbsolutePath());
    }

    private void selectLibraryJars() {
        JFileChooser chooser = createChooser();
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (File file : chooser.getSelectedFiles()) {
            if (builder.length() > 0) {
                builder.append(File.pathSeparator);
            }
            builder.append(file.getAbsolutePath());
        }
        libraryField.setText(builder.toString());
    }

    private void startObfuscation() {
        String input = inputField.getText().trim();
        String output = outputField.getText().trim();
        if (input.isEmpty() || output.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先选择输入 Jar 与输出 Jar", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        config.inputJar = input;
        config.outputJar = output;
        config.libraryJars = libraryField.getText().trim();
        config.obfuscateClassPath = classPathField.getText().trim();
        config.keepRules = keepField.getText().trim();
        config.stringReplacements = replaceField.getText().trim();
        config.renameClasses = classBox.isSelected();
        config.renameMembers = memberBox.isSelected();
        config.encryptStrings = stringBox.isSelected();
        config.stripDebugInfo = debugBox.isSelected();
        config.splitMethods = splitBox.isSelected();
        config.randomSplitClass = randomClassBox.isSelected();
        config.randomSplitMethodName = randomMethodBox.isSelected();

        logArea.setText("");
        progressBar.setValue(0);
        statusLabel.setText("混淆中...");
        setBusy(true);

        ObfuscateWorker worker = new ObfuscateWorker(config, this::appendLog);
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                progressBar.setValue((Integer) event.getNewValue());
                return;
            }
            if ("state".equals(event.getPropertyName()) && event.getNewValue() == SwingWorker.StateValue.DONE) {
                setBusy(false);
                try {
                    worker.get();
                    statusLabel.setText("完成");
                } catch (Exception exception) {
                    statusLabel.setText("失败");
                    appendLog("失败: " + exception);
                    JOptionPane.showMessageDialog(this, exception, "混淆失败", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        worker.execute();
    }

    private void appendLog(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setBusy(boolean busy) {
        inputField.setEnabled(!busy);
        outputField.setEnabled(!busy);
        libraryField.setEnabled(!busy);
        classPathField.setEnabled(!busy);
        keepField.setEnabled(!busy);
        replaceField.setEnabled(!busy);
        classBox.setEnabled(!busy);
        memberBox.setEnabled(!busy);
        stringBox.setEnabled(!busy);
        debugBox.setEnabled(!busy);
        splitBox.setEnabled(!busy);
        randomClassBox.setEnabled(!busy);
        randomMethodBox.setEnabled(!busy);
        inputButton.setEnabled(!busy);
        outputButton.setEnabled(!busy);
        libraryButton.setEnabled(!busy);
        startButton.setEnabled(!busy);
    }
}
