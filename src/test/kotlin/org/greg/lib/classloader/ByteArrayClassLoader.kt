package org.greg.lib.classloader;

import io.vavr.collection.Map;

public class ByteArrayClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    public ByteArrayClassLoader(Map<String, byte[]> classes) {
        super(ByteArrayClassLoader.class.getClassLoader());
        this.classes = classes;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name).getOrElseThrow(() -> new ClassNotFoundException(name));
        return defineClass(name, bytes, 0, bytes.length);
    }
}
