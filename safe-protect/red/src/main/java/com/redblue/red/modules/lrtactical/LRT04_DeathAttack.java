package com.redblue.red.modules.lrtactical;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * LRT-04: Attack while dead.
 * Exploits Vuln #6: neither handler checks player.isAlive().
 * Sends attack packets during death screen before respawn.
 */
public class LRT04_DeathAttack implements AttackModule {

    private static final ResourceLocation CHANNEL = new ResourceLocation("lrtactical", "network");
    private static final int IDX_MELEE_REQUEST = 4;
    private static final int IDX_PREPARE_MELEE = 5;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofFloat("range", "范围", 16f, 1f, 64f),
        ConfigParam.ofInt("batch_size", "批量大小", 32, 1, 48)
    );

    @Override public String id() { return "lrt04_deathattack"; }
    @Override public String name() { return "死亡攻击"; }
    @Override public String description() { return "死亡画面中继续攻击（无存活检查）"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 启用模块\n"
            + "2. 当你死亡时，点击「执行」或等待自动触发\n"
            + "3. 模块会自动检测死亡状态，存活时不会触发\n\n"
            + "[参数说明]\n"
            + "范围：攻击半径\n"
            + "批量大小：每个包携带的实体ID数量\n\n"
            + "[注意事项]\n"
            + "只在死亡画面中生效，存活状态下点击执行无效\n"
            + "需要服务器安装 LRTactical mod";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("lrtactical");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        // This exploit specifically works when the player IS dead
        if (mc.player.isAlive()) return;

        float range = getParam("range").map(ConfigParam::getFloat).orElse(16f);
        int batchSize = getParam("batch_size").map(ConfigParam::getInt).orElse(32);

        // Prepare attack (sets preparingAttack=true on server)
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_PREPARE_MELEE);
            buf.writeVarInt(0); // LEFT
            buf.writeDouble(px);
            buf.writeDouble(py);
            buf.writeDouble(pz);
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeDouble(1);
        });

        // Collect nearby targets
        List<Integer> targets = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity l) || !l.isAlive()) continue;
            if (mc.player.distanceTo(e) > range) continue;
            targets.add(e.getId());
        }

        // Send in batches
        for (int i = 0; i < targets.size(); i += batchSize) {
            List<Integer> batch = targets.subList(
                i, Math.min(i + batchSize, targets.size()));
            PacketForge.send(CHANNEL, buf -> {
                buf.writeByte(IDX_MELEE_REQUEST);
                buf.writeVarInt(0);
                buf.writeVarInt(batch.size());
                for (int id : batch) {
                    buf.writeVarInt(id);
                }
            });
        }
    }
}
