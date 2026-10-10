package com.github.rostmyr.jfibers.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_INTERFACE;

/** Resolves types from class resources without loading or initializing application classes. */
final class ClassHierarchy {
    private static final Type OBJECT = Type.getObjectType("java/lang/Object");
    private final ClassLoader loader;
    private final Map<String, Info> classes = new HashMap<>();

    ClassHierarchy(ClassLoader loader, ClassNode owner) {
        this.loader = loader;
        classes.put(owner.name, new Info(owner.superName, owner.interfaces, (owner.access & ACC_INTERFACE) != 0));
    }

    void generated(String name) {
        classes.put(name, new Info(FiberValidation.FIBER, List.of(), false));
    }

    ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return common(Type.getObjectType(first), Type.getObjectType(second)).getInternalName();
            }
        };
    }

    boolean isInterface(Type type) {
        return type.getSort() != Type.ARRAY && info(type.getInternalName()).isInterface;
    }

    Type superType(Type type) {
        if (type.getSort() == Type.ARRAY) {
            return OBJECT;
        }
        String parent = info(type.getInternalName()).parent;
        return parent == null ? null : Type.getObjectType(parent);
    }

    boolean assignable(Type target, Type value) {
        if (target.equals(value)) {
            return true;
        }
        if (!reference(target) || !reference(value)) {
            return false;
        }
        if (value.getInternalName().equals("null")) {
            return true;
        }
        if (value.getSort() == Type.ARRAY) {
            if (target.equals(OBJECT) || target.getInternalName().equals("java/lang/Cloneable")
                || target.getInternalName().equals("java/io/Serializable")) {
                return true;
            }
            if (target.getSort() != Type.ARRAY) {
                return false;
            }
            Type a = component(target);
            Type b = component(value);
            return reference(a) && reference(b) ? assignable(a, b) : a.equals(b);
        }
        return target.getSort() != Type.ARRAY && inherits(value.getInternalName(), target.getInternalName(), new HashSet<>());
    }

    private boolean inherits(String value, String target, Set<String> visited) {
        if (value.equals(target)) {
            return true;
        }
        if (!visited.add(value)) {
            return false;
        }
        Info type = info(value);
        if (type.parent != null && inherits(type.parent, target, visited)) {
            return true;
        }
        for (String implemented : type.interfaces) {
            if (inherits(implemented, target, visited)) {
                return true;
            }
        }
        return false;
    }

    private Type common(Type a, Type b) {
        if (assignable(a, b)) {
            return a;
        }
        if (assignable(b, a)) {
            return b;
        }
        if (a.getSort() == Type.ARRAY && b.getSort() == Type.ARRAY
            && reference(component(a)) && reference(component(b))) {
            return Type.getType("[" + common(component(a), component(b)).getDescriptor());
        }
        if (a.getSort() == Type.ARRAY || b.getSort() == Type.ARRAY || isInterface(a) || isInterface(b)) {
            return OBJECT;
        }
        do {
            a = superType(a);
        } while (a != null && !assignable(a, b));
        return a == null ? OBJECT : a;
    }

    private Info info(String name) {
        Info cached = classes.get(name);
        if (cached != null) {
            return cached;
        }
        try (InputStream bytes = loader.getResourceAsStream(name + ".class")) {
            if (bytes == null) {
                throw new IOException("class resource is missing");
            }
            ClassReader reader = new ClassReader(bytes);
            Info resolved = new Info(reader.getSuperName(), List.of(reader.getInterfaces()),
                (reader.getAccess() & ACC_INTERFACE) != 0);
            classes.put(name, resolved);
            return resolved;
        } catch (IOException e) {
            throw new FiberInstrumentationException("Cannot resolve " + name.replace('/', '.') + " on the application classpath", e);
        }
    }

    private static Type component(Type type) {
        return Type.getType(type.getDescriptor().substring(1));
    }

    static boolean reference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private record Info(String parent, List<String> interfaces, boolean isInterface) {
    }
}
