package com.redblue.red.modules.journeymap;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * JM-04: Multiplayer Options Spoof / Radar Invisibility
 *
 * Verified via javap:
 *   - Channel: journeymap:mp_options_req (separate SimpleChannel, discriminator=0)
 *   - Wire: writeByte(0) + writeByte(0x2A) + writeUtf(payload)
 *   - Handler: MultiplayerOptionsPacket.handle()
 *     - payload==null -> onMultiplayerOptionsOpen (server sends current settings)
 *     - payload!=null -> onMultiplayerOptionsSave (server saves settings)
 *
 * onMultiplayerOptionsSave() authorization:
 *   allowMultiplayerSettings.hasOption(isOp) -- ServerOption enum check
 *   If allowMultiplayerSettings is ALL or OPS_ONLY+isOp, proceeds.
 *   When allowed, deserializes JSON into MultiplayerProperties, extracts:
 *     - hideSelfUnderground (boolean)
 *     - visible (boolean)
 *   Saves to PlayerData.
 *
 * No GUI-open validation. Can send at any time without opening the options screen.
 * Setting visible=false hides you from all other players' JourneyMap radars.
 */
public class JM04_MultiplayerSpoof implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("journeymap", "mp_options_req");
    private static final int DISCRIMINATOR = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("action", "操作", "HIDE",
            "HIDE", "SHOW", "CUSTOM"),
        ConfigParam.ofString("custom_json", "自定义JSON", "")
            .visibleWhen("action", "CUSTOM")
    );

    @Override public String id() { return "jm04_multiplayer_spoof"; }
    @Override public String name() { return "雷达隐身"; }
    @Override public String description() { return "从其他玩家的JourneyMap雷达上隐藏自己"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择操作: HIDE=隐藏, SHOW=显示, CUSTOM=自定义JSON\n"
            + "2. 点击执行\n\n"
            + "[前提条件]\n"
            + "服务器 allowMultiplayerSettings 不为 NONE\n"
            + "(大多数服务器默认允许)\n\n"
            + "[效果]\n"
            + "HIDE: 从所有玩家的JourneyMap雷达上消失\n"
            + "SHOW: 恢复在雷达上的可见性\n"
            + "无需打开多人游戏选项GUI即可发送";
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

        String action = getParam("action")
            .map(ConfigParam::getString).orElse("HIDE");

        String payload;
        switch (action) {
            case "SHOW":
                payload = "{\"visible\":true,\"hideSelfUnderground\":false}";
                break;
            case "CUSTOM":
                payload = getParam("custom_json")
                    .map(ConfigParam::getString).orElse("{}");
                break;
            default: // HIDE
                payload = "{\"visible\":false,\"hideSelfUnderground\":true}";
                break;
        }

        final String fPayload = payload;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(DISCRIMINATOR);
            buf.writeByte(0x2A);
            buf.writeUtf(fPayload);
        });
    }
}
