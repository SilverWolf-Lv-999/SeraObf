package io.github.seraphina.obf.obfuscator;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class StringConstantMethodVisitor extends MethodVisitor {

    private final StringEncryptor encryptor;

    public StringConstantMethodVisitor(MethodVisitor methodVisitor, StringEncryptor encryptor) {
        super(Opcodes.ASM9, methodVisitor);
        this.encryptor = encryptor;
    }

    @Override
    public void visitLdcInsn(Object value) {
        if (!(value instanceof String)) {
            super.visitLdcInsn(value);
            return;
        }
        encryptor.emit(mv, (String) value);
    }
}
