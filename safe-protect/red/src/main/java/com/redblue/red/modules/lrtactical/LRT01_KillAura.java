package com.redblue.red.modules.lrtactical;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LRT-01: Kill Aura + Auto Attack (merged).
 * Vuln #1 (no distance validation) + #2 (preparingAttack bypass) + #4 (automation).
 *
 * When enabled, automatically attacks targets every tick interval.
 * Supports AOE (all in range) or single-target (nearest) mode.
 */
public class LRT01_KillAura implements AttackModule {

    private static final ResourceLocation CHANNEL = new ResourceLocation("lrtactical", "network");
    private static final int IDX_MELEE_REQUEST = 4;
    private static final int IDX_PREPARE_MELEE = 5;

    private long lastAttackTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "模式", "AOE", "AOE", "SINGLE"),
        ConfigParam.ofFloat("range", "范围", 32f, 1f, 128f),
        ConfigParam.ofInt("interval", "间隔(tick)", 10, 1, 200),
        ConfigParam.ofInt("batch_size", "批量大小(群攻)", 32, 1, 48).visibleWhen("mode", "AOE"),
        ConfigParam.ofEnum("action", "近战动作", "LEFT", "LEFT", "RIGHT"),
        ConfigParam.ofBool("target_players", "攻击玩家", true),
        ConfigParam.ofBool("target_mobs", "攻击怪物", true)
    );

    @Override public String id() { return "lrt01_killaura"; }
    @Override public String name() { return "杀戮光环"; }
    @Override public String description() { return "自动攻击：范围群攻或单体循环"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 启用模块后自动在 tick 中循环攻击\n"
            + "2. 模式选择：\n"
            + "   AOE = 攻击范围内所有目标（群攻）\n"
            + "   SINGLE = 只攻击最近的一个目标\n"
            + "3. 可分别开关「攻击玩家」和「攻击怪物」\n"
            + "4. 批量大小控制每个包携带的实体ID数量（最大48）\n\n"
            + "[参数说明]\n"
            + "范围：攻击半径，服务端无距离限制，但客户端只能看到渲染范围内的实体\n"
            + "间隔：每次攻击的 tick 间隔，越小越快\n"
            + "近战动作：LEFT=左键攻击，RIGHT=右键攻击\n\n"
            + "[注意事项]\n"
            + "需要服务器安装 LRTactical mod 才能生效";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("lrtactical");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    /** Manual one-shot from GUI Execute button — fires immediately ignoring interval */
    @Override
    public void execute(Minecraft mc) {
        doAttack(mc);
    }

    /** Auto-loop: called every client tick while enabled */
    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(10);
        long now = mc.level.getGameTime();
        if (now - lastAttackTick < interval) return;
        lastAttackTick = now;

        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        float range = getParam("range").map(ConfigParam::getFloat).orElse(32f);
        int actionOrdinal = getParam("action").map(p -> p.getString().equals("RIGHT") ? 1 : 0).orElse(0);
        boolean targetPlayers = getParam("target_players").map(ConfigParam::getBool).orElse(true);
        boolean targetMobs = getParam("target_mobs").map(ConfigParam::getBool).orElse(true);
        String mode = getParam("mode").map(ConfigParam::getString).orElse("AOE");

        // Collect valid targets
        List<Entity> targets = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!(e instanceof LivingEntity living) || !living.isAlive()) continue;
            if (!targetPlayers && e instanceof Player) continue;
            if (!targetMobs && !(e instanceof Player)) continue;
            if (mc.player.distanceTo(e) > range) continue;
            targets.add(e);
        }

        if (targets.isEmpty()) return;

        // Single mode: only attack nearest
        if ("SINGLE".equals(mode)) {
            targets.sort(Comparator.comparingDouble(e -> mc.player.distanceTo(e)));
            targets = List.of(targets.get(0));
        }

        // Step 1: Prepare
        sendPrepare(mc, actionOrdinal);

        // Step 2: Attack in batches
        int batchSize = getParam("batch_size").map(ConfigParam::getInt).orElse(32);
        List<Integer> ids = targets.stream().map(Entity::getId).toList();

        for (int i = 0; i < ids.size(); i += batchSize) {
            List<Integer> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
            sendAttack(actionOrdinal, batch);
        }
    }

    private void sendPrepare(Minecraft mc, int actionOrdinal) {
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();
        float yRot = mc.player.getYRot();
        float xRot = mc.player.getXRot();
        double dirX = -Math.sin(Math.toRadians(yRot)) * Math.cos(Math.toRadians(xRot));
        double dirY = -Math.sin(Math.toRadians(xRot));
        double dirZ = Math.cos(Math.toRadians(yRot)) * Math.cos(Math.toRadians(xRot));

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_PREPARE_MELEE);
            buf.writeVarInt(actionOrdinal);
            buf.writeDouble(px);
            buf.writeDouble(py);
            buf.writeDouble(pz);
            buf.writeDouble(dirX);
            buf.writeDouble(dirY);
            buf.writeDouble(dirZ);
        });
    }

    private void sendAttack(int actionOrdinal, List<Integer> entityIds) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_MELEE_REQUEST);
            buf.writeVarInt(actionOrdinal);
            buf.writeVarInt(entityIds.size());
            for (int id : entityIds) {
                buf.writeVarInt(id);
            }
        });
    }
}
