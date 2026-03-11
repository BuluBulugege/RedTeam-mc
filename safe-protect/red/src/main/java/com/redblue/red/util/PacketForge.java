package com.redblue.red.util;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Packet sender with two modes:
 * 1. Raw mode: sends ServerboundCustomPayloadPacket directly (fast, works for some mods)
 * 2. Reflection mode: uses target mod's own SimpleChannel.sendToServer() via reflection
 *    (guaranteed compatible, needed for mods where raw mode is silently dropped)
 */
public class PacketForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("NetHandler");

    // Cache reflected SimpleChannel instances and message constructors
    private static final Map<String, Object> channelCache = new ConcurrentHashMap<>();

    // ---- Raw mode (original) ----

    public static void send(String namespace, String path, Consumer<FriendlyByteBuf> encoder) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        encoder.accept(buf);
        conn.getConnection().send(
            new ServerboundCustomPayloadPacket(new ResourceLocation(namespace, path), buf)
        );
    }

    public static void send(ResourceLocation channel, Consumer<FriendlyByteBuf> encoder) {
        send(channel.getNamespace(), channel.getPath(), encoder);
    }

    // ---- Reflection mode: use target mod's SimpleChannel ----

    /** Get or cache a static field value (typically SimpleChannel). */
    private static Object getStaticField(String className, String fieldName) {
        String cacheKey = className + "#" + fieldName;
        return channelCache.computeIfAbsent(cacheKey, k -> {
            try {
                Class<?> clz = Class.forName(className);
                Field f = clz.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(null);
            } catch (Exception e) {
                LOGGER.error("Failed to get field {}", cacheKey, e);
                return null;
            }
        });
    }

    public static boolean sendViaChannel(String modClass, String channelField, Object msg) {
        try {
            Object channel = getStaticField(modClass, channelField);
            if (channel == null) return false;
            ((SimpleChannel) channel).sendToServer(msg);
            return true;
        } catch (Exception e) {
            LOGGER.error("sendViaChannel(obj) failed: {}", msg.getClass().getName(), e);
            return false;
        }
    }

    public static boolean sendViaChannel(String modClass, String channelField, String msgClass,
                                         Class<?>[] ctorArgTypes, Object[] ctorArgs) {
        try {
            Object channel = getStaticField(modClass, channelField);
            if (channel == null) return false;
            Class<?> msgClz = Class.forName(msgClass);
            var ctor = msgClz.getDeclaredConstructor(ctorArgTypes);
            ctor.setAccessible(true);
            Object msg = ctor.newInstance(ctorArgs);
            ((SimpleChannel) channel).sendToServer(msg);
            return true;
        } catch (Exception e) {
            LOGGER.error("sendViaChannel failed: {}", msgClass, e);
            return false;
        }
    }

    public static boolean sendViaStaticMethod(String handlerClass, String methodName, Object msg) {
        try {
            Class<?> clz = Class.forName(handlerClass);
            var method = clz.getMethod(methodName, msg.getClass().getSuperclass() != Object.class
                    ? msg.getClass().getSuperclass() : msg.getClass());
            method.invoke(null, msg);
            return true;
        } catch (NoSuchMethodException e) {
            try {
                Class<?> clz = Class.forName(handlerClass);
                for (Class<?> iface : msg.getClass().getInterfaces()) {
                    try {
                        var method = clz.getMethod(methodName, iface);
                        method.invoke(null, msg);
                        return true;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (Exception e2) {
                LOGGER.error("sendViaStaticMethod failed: {}", handlerClass, e2);
            }
            LOGGER.error("sendViaStaticMethod no matching method: {}.{}({})",
                    handlerClass, methodName, msg.getClass().getName());
            return false;
        } catch (Exception e) {
            LOGGER.error("sendViaStaticMethod failed: {}", handlerClass, e);
            return false;
        }
    }
}
