package com.redblue.red.modules.legendarysurvival;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * LSO-01: Infinite Body Part Healing (BodyPartHealingTimeMessage exploit)
 *
 * Vulnerabilities exploited:
 * A) healingItem field not validated against held item - can claim any registered healing item
 * B) consumeItem flag client-controlled - set false to never consume
 * C) applyEffect flag client-controlled - set true to always get RECOVERY effect
 * D) No server-side healingCharges counter - unlimited uses
 * E) No GUI open state check - can send anytime
 *
 * Best item: "legendarysurvivaloverhaul:medkit" (healingValue=8.0, recovery amp=2, duration=1400)
 *
 * Approach: Reflection call to BodyPartHealingTimeMessage.sendToServer() or
 * construct message object and send via NetworkHandler.INSTANCE.sendToServer().
 */
public class LSO01_InfiniteHeal implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private static final String NETWORK_HANDLER_CLASS =
            "sfiomn.legendarysurvivaloverhaul.network.NetworkHandler";
    private static final String MSG_CLASS =
            "sfiomn.legendarysurvivaloverhaul.network.packets.BodyPartHealingTimeMessage";
    private static final String BODY_PART_ENUM_CLASS =
            "sfiomn.legendarysurvivaloverhaul.api.bodydamage.BodyPartEnum";

    /** All 8 body parts in enum declaration order */
    private static final String[] BODY_PARTS = {
            "HEAD", "RIGHT_ARM", "LEFT_ARM", "CHEST",
            "RIGHT_LEG", "RIGHT_FOOT", "LEFT_LEG", "LEFT_FOOT"
    };

    /** Known healing items (namespace:path) sorted by healingValue descending */
    private static final String[] HEALING_ITEMS = {
            "legendarysurvivaloverhaul:medkit",
            "legendarysurvivaloverhaul:tonic",
            "legendarysurvivaloverhaul:bandage",
            "legendarysurvivaloverhaul:plaster",
            "legendarysurvivaloverhaul:healing_herbs"
    };

    private static final String[] HEALING_ITEM_LABELS = {
            "Medkit (8.0hp, amp2)",
            "Tonic (5.0hp, amp1)",
            "Bandage (3.0hp, amp1)",
            "Plaster (3.0hp, amp0)",
            "Healing Herbs (2.0hp, amp0)"
    };

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofEnum("mode", "模式", "ALL_PARTS",
                    "ALL_PARTS", "SINGLE_PART"),
            ConfigParam.ofEnum("body_part", "目标部位", "HEAD", BODY_PARTS)
                    .visibleWhen("mode", "SINGLE_PART"),
            ConfigParam.ofEnum("healing_item", "伪造物品", HEALING_ITEM_LABELS[0], HEALING_ITEM_LABELS),
            ConfigParam.ofBool("consume_item", "消耗物品(false=不消耗)", false),
            ConfigParam.ofBool("apply_effect", "施加恢复效果", true),
            ConfigParam.ofBool("use_main_hand", "使用主手", true),
            ConfigParam.ofInt("repeat", "单次重复", 1, 1, 64),
            ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lso01_infinite_heal"; }
    @Override public String name() { return "无限治疗"; }
    @Override public String description() { return "伪造治疗物品+不消耗+无限恢复效果(BodyPartHealingTimeMessage)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
                + "1. 选择模式: ALL_PARTS=治疗全身8个部位, SINGLE_PART=治疗单个部位\n"
                + "2. 选择伪造物品(Medkit效果最强)\n"
                + "3. consume_item=false 不消耗手持物品\n"
                + "4. apply_effect=true 每次获得RECOVERY恢复效果\n"
                + "5. 点击执行或启用自动模式\n\n"
                + "[原理]\n"
                + "服务端不验证手持物品与声称物品一致性\n"
                + "consumeItem/applyEffect标志由客户端控制\n"
                + "无服务端healingCharges计数器\n"
                + "无GUI打开状态验证";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("legendarysurvivaloverhaul");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int repeat = getParam("repeat").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < repeat; i++) {
            doHeal();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doHeal();
    }

    private void doHeal() {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("ALL_PARTS");
        String healingItem = resolveHealingItem();
        boolean consume = getParam("consume_item").map(ConfigParam::getBool).orElse(false);
        boolean applyEffect = getParam("apply_effect").map(ConfigParam::getBool).orElse(true);
        boolean mainHand = getParam("use_main_hand").map(ConfigParam::getBool).orElse(true);

        if ("ALL_PARTS".equals(mode)) {
            for (String part : BODY_PARTS) {
                sendHealPacket(part, healingItem, mainHand, consume, applyEffect);
            }
        } else {
            String part = getParam("body_part").map(ConfigParam::getString).orElse("HEAD");
            sendHealPacket(part, healingItem, mainHand, consume, applyEffect);
        }
    }

    private String resolveHealingItem() {
        String label = getParam("healing_item").map(ConfigParam::getString).orElse(HEALING_ITEM_LABELS[0]);
        for (int i = 0; i < HEALING_ITEM_LABELS.length; i++) {
            if (HEALING_ITEM_LABELS[i].equals(label)) return HEALING_ITEMS[i];
        }
        return HEALING_ITEMS[0];
    }

    /**
     * Send via reflection: construct BodyPartHealingTimeMessage from NBT tag
     * and use NetworkHandler.INSTANCE.sendToServer(msg).
     *
     * The message has a constructor: BodyPartHealingTimeMessage(Tag nbt)
     * We build the CompoundTag ourselves with forged values.
     */
    private void sendHealPacket(String bodyPart, String healingItem,
                                boolean mainHand, boolean consumeItem, boolean applyEffect) {
        try {
            // Build the forged CompoundTag
            CompoundTag nbt = new CompoundTag();
            nbt.putString("bodyPartEnum", bodyPart);
            nbt.putString("healingItem", healingItem);
            nbt.putBoolean("mainHand", mainHand);
            nbt.putBoolean("consumeItem", consumeItem);
            nbt.putBoolean("applyEffect", applyEffect);

            // Construct message via Tag constructor
            Class<?> msgClz = Class.forName(MSG_CLASS);
            Constructor<?> ctor = msgClz.getDeclaredConstructor(Tag.class);
            ctor.setAccessible(true);
            Object msg = ctor.newInstance(nbt);

            // Send via NetworkHandler.INSTANCE.sendToServer()
            PacketForge.sendViaChannel(NETWORK_HANDLER_CLASS, "INSTANCE", msg);
        } catch (Exception e) {
            LOGGER.error("LSO01 sendHealPacket failed", e);
        }
    }
}
