package com.redblue.red.modules.pingwheel;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * PW-02: Channel Hijack / Eavesdrop
 *
 * Verified against source:
 *   - Channel: ping-wheel-c2s:update-channel (EventNetworkChannel, NO discriminator)
 *   - ServerCore.onChannelUpdate(): no auth, no password, pure string match
 *   - Wire: writeUtf(channel, 128)
 *   - After joining a channel, attacker receives all pings broadcast to that channel
 *   - Can also send forged pings into the channel (combine with PW01)
 *   - ServerCore line 80: channel.equals(PLAYER_CHANNELS.getOrDefault(...))
 */
public class PW02_ChannelSpy implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("ping-wheel-c2s", "update-channel");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofString("target_channel", "目标频道名称", ""),
        ConfigParam.ofEnum("action", "操作", "JOIN",
            "JOIN", "LEAVE")
    );

    @Override public String id() { return "pw02_channel_spy"; }
    @Override public String name() { return "频道窥探"; }
    @Override public String description() { return "监听PingWheel频道获取其他玩家标记信息"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 输入目标频道名称（如'admin-team'、'staff'）\n"
            + "2. 操作选择JOIN：切换到该频道，接收该频道所有标记\n"
            + "3. 操作选择LEAVE：切换回默认频道（空字符串）\n\n"
            + "[参数说明]\n"
            + "目标频道名称：要加入的频道名称\n"
            + "操作：JOIN加入频道，LEAVE离开频道\n\n"
            + "[注意事项]\n"
            + "加入频道后可接收该频道所有成员的标记\n"
            + "可配合PW01向该频道注入伪造标记";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("pingwheel");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        String action = getParam("action").map(ConfigParam::getString).orElse("JOIN");
        String targetChannel;

        if ("LEAVE".equals(action)) {
            targetChannel = "";
        } else {
            targetChannel = getParam("target_channel")
                .map(ConfigParam::getString).orElse("");
        }

        PacketForge.send(CHANNEL, buf -> {
            buf.writeUtf(targetChannel, 128);
        });
    }
}
