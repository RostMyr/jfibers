package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.tree.ClassNode;

public class ClassTransformer {
    protected ClassTransformer ct;

    public ClassTransformer(ClassTransformer ct) {
        this.ct = ct;
    }

    public void transform(ClassNode cn) {
        if (ct != null) {
            ct.transform(cn);
        }
    }
}
