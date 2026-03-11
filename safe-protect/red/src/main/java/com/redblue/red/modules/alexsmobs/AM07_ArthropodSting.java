package com.redblue.red.modules.alexsmobs;

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
 * AM-06: Remote debilitating sting via MessageTarantulaHawkSting (index 12).
 *
 * Wire: writeInt(hawkEntityId) + writeInt(spiderEntityId)
 *
 * Checks: hawk instanceof EntityTarantulaHawk,
 *         spider instanceof LivingEntity with MobType.ARTHROPOD.
 * No distance check (hawk-to-spider or player-to-either), no ownership, no cooldown.
 * Applies DEBILITATING_STING effect for 2400 ticks (2 minutes).
 */
public class AM07_ArthropodSting implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_HAWK_STING = 12;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEntity("hawk_id", "狼蛛鹰"),
        ConfigParam.ofEnum("target_mode", "目标模式",
                "SCAN", "SCAN", "MANUAL"),
        ConfigParam.ofEntity("spider_id", "蜘蛛(手动)")
                .visibleWhen("target_mode", "MANUAL"),
        ConfigParam.ofFloat("range", "扫描范围", 128f, 1f, 256f)
                .visibleWhen("target_mode", "SCAN"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 10, 1, 200)
    );

    @Override public String id() { return "am07_arthropod_sting"; }
    @Override public String name() { return "节肢蛰刺"; }
    @Override public String description() {
        return "远程让狼蛛鹰对节肢生物施加麻痹";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 用准星对准世界中的狼蛛鹰实体，设置 hawk_id\n"
            + "2. 选择目标模式：SCAN（自动扫描）或 MANUAL（手动指定）\n"
            + "3. SCAN模式：自动搜索范围内所有节肢类生物并逐一施加效果\n"
            + "4. MANUAL模式：用准星对准目标蜘蛛实体，设置 spider_id\n"
            + "5. 启用后执行或自动tick循环执行\n\n"
            + "[参数说明]\n"
            + "hawk_id - 用准星选取的狼蛛鹰实体\n"
            + "target_mode - 目标模式：SCAN 自动扫描 / MANUAL 手动指定\n"
            + "spider_id - 手动模式下用准星选取的目标蜘蛛实体\n"
            + "range - 扫描模式下的搜索范围（方块数）\n"
            + "interval - 自动模式下每次执行的间隔tick数\n\n"
            + "[注意事项]\n"
            + "SCAN模式会对范围内所有存活的节肢类生物发送效果\n"
            + "MANUAL模式下需要手动用准星选取目标蜘蛛实体";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("alexsmobs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        doSting(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(10);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doSting(mc);
    }

    private void doSting(Minecraft mc) {
        int hawkId = getParam("hawk_id").map(ConfigParam::getInt).orElse(0);
        String mode = getParam("target_mode").map(ConfigParam::getString).orElse("SCAN");

        if ("MANUAL".equals(mode)) {
            int spiderId = getParam("spider_id").map(ConfigParam::getInt).orElse(0);
            sendSting(hawkId, spiderId);
            return;
        }

        // SCAN: find all living entities in range
        // Server filters by MobType.ARTHROPOD, so we send for all LivingEntity
        float range = getParam("range").map(ConfigParam::getFloat).orElse(128f);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity living)) continue;
            if (!living.isAlive()) continue;
            if (mc.player.distanceTo(e) > range) continue;
            sendSting(hawkId, e.getId());
        }
    }

    private void sendSting(int hawkId, int spiderId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_HAWK_STING);
            buf.writeInt(hawkId);
            buf.writeInt(spiderId);
        });
    }
}
