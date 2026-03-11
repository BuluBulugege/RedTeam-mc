package com.redblue.red.hider;

import net.minecraftforge.network.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

public class HandshakeHider {

    private static final Logger LOG = LoggerFactory.getLogger("HandshakeHider");

    public static void hide(String modId) {
        try {
            removeFromNetworkRegistry(modId);
            LOG.info("Hidden mod from FML handshake: {}", modId);
        } catch (Exception e) {
            LOG.error("Failed to hide from handshake: {}", modId, e);
        }
    }

    private static void removeFromNetworkRegistry(String modId) throws Exception {
        for (String fieldName : new String[]{"instances", "channels"}) {
            try {
                Field field = NetworkRegistry.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> map = (Map<Object, Object>) value;
                    map.entrySet().removeIf(entry ->
                            entry.getKey().toString().contains(modId));
                    LOG.debug("Cleaned NetworkRegistry.{}", fieldName);
                }
            } catch (NoSuchFieldException ignored) {}
        }
    }
}
