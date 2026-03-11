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
import java.util.Comparator;
import java.util.List;

/**
 * AM-04: Unvalidated damage via MessageHurtMultipart (index 2).
 *
 * Wire: writeInt(partEntityId) + writeInt(parentEntityId) + writeFloat(damage) + writeUtf(damageType)
 *
 * No distance check, no damage cap, no damageType whitelist, no cooldown.
 * Can send Float.MAX_VALUE to one-shot any multipart entity.
 * Fallback path: if part is null but parent is multipart, calls parent.hurt() directly.
 */
public class AM02_HurtMultipart implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_HURT_MULTIPART = 2;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "目标模式", "NEAREST",
                "NEAREST", "ALL_IN_RANGE", "BY_ID"),
        ConfigParam.ofFloat("damage", "伤害值", 1000f, 1f, 3.4028235E38f),
        ConfigParam.ofString("damage_type", "伤害类型",
                "minecraft:player_attack"),
        ConfigParam.ofFloat("range", "扫描范围", 64f, 1f, 256f),
        ConfigParam.ofEntity("target_entity_id", "目标实体(手动)")
                .visibleWhen("mode", "BY_ID"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 10, 1, 200)
    );

    @Override public String id() { return "am02_hurt_multipart"; }
    @Override public String name() { return "多部件秒杀"; }
    @Override public String description() {
        return "对多部件生物造成无上限伤害";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 启用模块\n"
            + "2. 选择目标模式：NEAREST(最近实体)、ALL_IN_RANGE(范围全部)、BY_ID(指定实体)\n"
            + "3. 设置伤害值和伤害类型\n"
            + "4. 点击执行或启用自动模式\n\n"
            + "[参数说明]\n"
            + "目标模式：选择目标的方式\n"
            + "  NEAREST — 自动选取范围内最近的实体\n"
            + "  ALL_IN_RANGE — 对范围内所有实体生效\n"
            + "  BY_ID — 手动指定目标实体\n"
            + "伤害值：每次造成的伤害数值，默认为最大值\n"
            + "伤害类型：伤害的类型标识，默认为 minecraft:player_attack\n"
            + "扫描范围：扫描实体的最大距离(格)\n"
            + "目标实体(手动)：BY_ID 模式下，用准星选取或手动输入目标实体\n"
            + "自动间隔(tick)：自动模式下每次执行的间隔";
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
        doAttack(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(10);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        float damage = getParam("damage").map(ConfigParam::getFloat).orElse(Float.MAX_VALUE);
        String damageType = getParam("damage_type").map(ConfigParam::getString)
                .orElse("minecraft:player_attack");
        String mode = getParam("mode").map(ConfigParam::getString).orElse("NEAREST");

        if ("BY_ID".equals(mode)) {
            int targetId = getParam("target_entity_id").map(ConfigParam::getInt).orElse(0);
            sendHurt(targetId, targetId, damage, damageType);
            return;
        }

        float range = getParam("range").map(ConfigParam::getFloat).orElse(64f);
        List<Entity> targets = new ArrayList<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity living) || !living.isAlive()) continue;
            if (mc.player.distanceTo(e) > range) continue;
            targets.add(e);
        }

        if (targets.isEmpty()) return;

        if ("NEAREST".equals(mode)) {
            targets.sort(Comparator.comparingDouble(e -> mc.player.distanceTo(e)));
            Entity t = targets.get(0);
            sendHurt(t.getId(), t.getId(), damage, damageType);
        } else {
            for (Entity t : targets) {
                sendHurt(t.getId(), t.getId(), damage, damageType);
            }
        }
    }

    private void sendHurt(int partId, int parentId, float damage, String damageType) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_HURT_MULTIPART);
            buf.writeInt(partId);
            buf.writeInt(parentId);
            buf.writeFloat(damage);
            buf.writeUtf(damageType);
        });
    }
}
