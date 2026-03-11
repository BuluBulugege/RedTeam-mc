package com.redblue.red.modules.journeymap;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * JM-02: Arbitrary Teleport via TeleportPacket
 *
 * Verified via javap:
 *   - Channel: journeymap:teleport_req (separate SimpleChannel, discriminator=0)
 *   - Wire format: writeByte(0) + writeDouble(x) + writeDouble(y) + writeDouble(z) + writeUtf(dim)
 *   - TeleportPacket.encode() writes: double x, double y, double z, writeUtf(dim) -- NO 0x2A marker
 *   - Handler: PacketHandler.handleTeleportPacket() -> JourneyMapTeleport.attemptTeleport()
 *
 * Authorization chain in attemptTeleport():
 *   1. isTeleportAvailable(entity, location) -- checks dim teleportEnabled config
 *   2. OR player.abilities.instabuild (creative)
 *   3. OR PlayerList.isOp(gameProfile)
 *   4. OR Journeymap.isOp(player)
 *   If ANY is true, teleport proceeds.
 *
 * No distance check, no rate limit, no cooldown.
 * Cross-dimension teleport supported via dim string lookup.
 *
 * Prerequisite: teleportEnabled=true for the dimension, OR player is OP/creative.
 */
public class JM01_Teleport implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("journeymap", "teleport_req");
    private static final int DISCRIMINATOR = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("coord_source", "坐标来源", "CURRENT", "CURRENT", "CUSTOM"),
        ConfigParam.ofFloat("custom_x", "X 坐标", 0f, -30000000f, 30000000f)
            .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("custom_y", "Y 坐标", 64f, -64f, 320f)
            .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("custom_z", "Z 坐标", 0f, -30000000f, 30000000f)
            .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofEnum("dimension", "目标维度", "minecraft:overworld",
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
        ConfigParam.ofBool("auto_repeat", "自动重复", false),
        ConfigParam.ofInt("interval", "重复间隔(tick)", 20, 1, 200)
            .visibleWhen("auto_repeat", "true")
    );

    private long lastTickTime = 0;

    @Override public String id() { return "jm01_teleport"; }
    @Override public String name() { return "任意传送"; }
    @Override public String description() { return "无距离限制跨维度传送 (需服务器启用teleport)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择坐标来源: CURRENT=当前位置, CUSTOM=自定义坐标\n"
            + "2. 如选CUSTOM, 填写目标 X/Y/Z 坐标\n"
            + "3. 选择目标维度\n"
            + "4. 点击执行即可传送\n\n"
            + "[前提条件]\n"
            + "服务器 JourneyMap 配置中 teleportEnabled=true\n"
            + "或者玩家为 OP / 创造模式\n\n"
            + "[注意事项]\n"
            + "无距离限制, 无冷却, 可跨维度\n"
            + "如果维度字符串不匹配已加载维度, 传送会失败";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("journeymap");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        sendTeleport(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean autoRepeat = getParam("auto_repeat").map(ConfigParam::getBool).orElse(false);
        if (!autoRepeat) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTickTime < interval) return;
        lastTickTime = now;
        sendTeleport(mc);
    }

    private void sendTeleport(Minecraft mc) {
        String source = getParam("coord_source").map(ConfigParam::getString).orElse("CURRENT");
        double x, y, z;

        if ("CUSTOM".equals(source)) {
            x = getParam("custom_x").map(ConfigParam::getFloat).orElse(0f);
            y = getParam("custom_y").map(ConfigParam::getFloat).orElse(64f);
            z = getParam("custom_z").map(ConfigParam::getFloat).orElse(0f);
        } else {
            x = mc.player.getX();
            y = mc.player.getY();
            z = mc.player.getZ();
        }

        String dim = getParam("dimension").map(ConfigParam::getString).orElse("minecraft:overworld");

        final double fx = x, fy = y, fz = z;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(DISCRIMINATOR);
            buf.writeDouble(fx);
            buf.writeDouble(fy);
            buf.writeDouble(fz);
            // writeUtf = m_130070_ : writes VarInt length prefix + UTF-8 bytes
            buf.writeUtf(dim);
        });
    }
}
