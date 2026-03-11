package com.redblue.red.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Loads classes from a jar byte array in memory. No temp files on disk.
 */
public class InMemoryClassLoader extends URLClassLoader {

    private final Map<String, byte[]> classBytes = new HashMap<>();
    private final Map<String, byte[]> resources = new HashMap<>();

    public InMemoryClassLoader(byte[] jarBytes, ClassLoader parent) throws IOException {
        super(new URL[0], parent);
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                byte[] data = jis.readAllBytes();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = name.replace('/', '.').substring(0, name.length() - 6);
                    classBytes.put(className, data);
                } else {
                    resources.put(name, data);
                }
            }
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classBytes.get(name);
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public java.io.InputStream getResourceAsStream(String name) {
        byte[] data = resources.get(name);
        if (data != null) return new ByteArrayInputStream(data);
        return super.getResourceAsStream(name);
    }
}
