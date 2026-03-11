package com.redblue.red.modules.alexsmobs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * AM-08: Remote force dismount via MessageCrowDismount (index 4)
 * and MessageMosquitoDismount (index 1).
 *
 * CrowDismount wire: writeInt(riderId) + writeInt(mountId)
 *   Checks: rider instanceof EntityCrow, mount != null
 *
 * MosquitoDismount wire: writeInt(riderId) + writeInt(mountId)
 *   Checks: rider instanceof CrimsonMosquito|BaldEagle|Enderiophage, mount != null
 *
 * No distance check, no ownership check on either.
 */
public class AM05_ForceDismount implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_MOSQUITO_DISMOUNT = 1;
    private static final int IDX_CROW_DISMOUNT = 4;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("dismount_type", "解除类型",
                "BOTH", "BOTH", "CROW_ONLY", "MOSQUITO_ONLY"),
        ConfigParam.ofEntity("rider_id", "骑乘者(手动)")
                .visibleWhen("target_mode", "MANUAL"),
        ConfigParam.ofEntity("mount_id", "坐骑(手动)")
                .visibleWhen("target_mode", "MANUAL"),
        ConfigParam.ofEnum("target_mode", "目标模式",
                "SCAN", "SCAN", "MANUAL"),
        ConfigParam.ofFloat("range", "扫描范围", 128f, 1f, 256f)
                .visibleWhen("target_mode", "SCAN"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 5, 1, 200)
    );

    @Override public String id() { return "am05_force_dismount"; }
    @Override public String name() { return "强制下坐骑"; }
    @Override public String description() {
        return "强制骑乘乌鸦/蚊子/老鹰的玩家下坐骑";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择目标模式：\n"
            + "   - 扫描：自动搜索范围内所有骑乘中的实体并强制下坐骑。\n"
            + "   - 手动：手动指定骑乘者和坐骑的实体。\n"
            + "2. 扫描模式下设置扫描范围即可；手动模式下用准星选取骑乘者和坐骑。\n"
            + "3. 选择解除类型来限定作用的坐骑种类。\n"
            + "4. 点击「执行」或开启自动模式。\n\n"
            + "[参数说明]\n"
            + "dismount_type — 解除类型：BOTH(全部) / CROW_ONLY(仅乌鸦) / MOSQUITO_ONLY(仅蚊子)。\n"
            + "rider_id      — 骑乘者实体，仅手动模式使用，准星选取或手动输入。\n"
            + "mount_id      — 坐骑实体，仅手动模式使用，准星选取或手动输入。\n"
            + "target_mode   — 目标模式：SCAN(扫描) / MANUAL(手动)。\n"
            + "range         — 扫描范围（格），仅扫描模式生效。\n"
            + "interval      — 自动模式下每次发送的间隔，单位为 tick。";
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
        doDismount(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doDismount(mc);
    }

    private void doDismount(Minecraft mc) {
        String targetMode = getParam("target_mode").map(ConfigParam::getString).orElse("SCAN");
        String type = getParam("dismount_type").map(ConfigParam::getString).orElse("BOTH");

        if ("MANUAL".equals(targetMode)) {
            int riderId = getParam("rider_id").map(ConfigParam::getInt).orElse(0);
            int mountId = getParam("mount_id").map(ConfigParam::getInt).orElse(0);
            if (!"MOSQUITO_ONLY".equals(type)) {
                sendCrowDismount(riderId, mountId);
            }
            if (!"CROW_ONLY".equals(type)) {
                sendMosquitoDismount(riderId, mountId);
            }
            return;
        }

        // SCAN mode: find all riding entities in range
        float range = getParam("range").map(ConfigParam::getFloat).orElse(128f);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (!e.isPassenger()) continue;
            if (mc.player.distanceTo(e) > range) continue;

            Entity vehicle = e.getVehicle();
            if (vehicle == null) continue;

            // Try both dismount types since we can't check entity class
            // without importing alexsmobs classes. Server will filter by type.
            if (!"MOSQUITO_ONLY".equals(type)) {
                sendCrowDismount(e.getId(), vehicle.getId());
            }
            if (!"CROW_ONLY".equals(type)) {
                sendMosquitoDismount(e.getId(), vehicle.getId());
            }
        }
    }

    private void sendCrowDismount(int riderId, int mountId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_CROW_DISMOUNT);
            buf.writeInt(riderId);
            buf.writeInt(mountId);
        });
    }

    private void sendMosquitoDismount(int riderId, int mountId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_MOSQUITO_DISMOUNT);
            buf.writeInt(riderId);
            buf.writeInt(mountId);
        });
    }
}
