package com.redblue.red.modules.immersiveaircraft;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * IA-02: Arbitrary vehicle velocity injection via DISMOUNT command.
 *
 * CommandMessage.receive() DISMOUNT branch calls vehicle.m_20334_(fx, fy, fz)
 * (setDeltaMovement) with raw client-provided doubles -- no magnitude check,
 * no clamp, no validation.
 *
 * Precondition: player must be riding (getRootVehicle()) a VehicleEntity.
 * Side effect: player is dismounted (stopRiding + setShiftKeyDown(false)).
 *
 * Wire format (index 1): writeInt(key_ordinal) + writeDouble(fx) + writeDouble(fy) + writeDouble(fz)
 * DISMOUNT ordinal = 0
 */
public class IA02_VelocityInject implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ic_air", "main");
    private static final int IDX_COMMAND = 1;
    private static final int KEY_DISMOUNT = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("preset", "速度预设", "CUSTOM",
                "CUSTOM", "LAUNCH_UP", "LAUNCH_FORWARD", "VOID_YEET", "CHUNK_STORM"),
        ConfigParam.ofFloat("vx", "速度 X", 0f, -1000000f, 1000000f)
                .visibleWhen("preset", "CUSTOM"),
        ConfigParam.ofFloat("vy", "速度 Y", 1000f, -1000000f, 1000000f)
                .visibleWhen("preset", "CUSTOM"),
        ConfigParam.ofFloat("vz", "速度 Z", 0f, -1000000f, 1000000f)
                .visibleWhen("preset", "CUSTOM")
    );

    @Override public String id() { return "ia02_velocity_inject"; }
    @Override public String name() { return "载具速度注入"; }

    @Override
    public String description() {
        return "下车时注入任意速度向量，将载具发射到极端位置";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 骑乘任意 Immersive Aircraft 载具\n"
            + "2. 选择速度预设或自定义 XYZ 速度值\n"
            + "3. 点击「执行」发送 DISMOUNT 命令并注入速度\n\n"
            + "[预设说明]\n"
            + "- LAUNCH_UP: 垂直发射 (Y=100000)\n"
            + "- LAUNCH_FORWARD: 水平发射 (X=100000)\n"
            + "- VOID_YEET: 向下发射到虚空 (Y=-100000)\n"
            + "- CHUNK_STORM: 极端速度触发区块加载风暴\n"
            + "- CUSTOM: 自定义 XYZ 速度值\n\n"
            + "[前置条件]\n"
            + "- 必须骑乘 VehicleEntity\n"
            + "- 执行后你会被下车，载具以注入速度飞走\n\n"
            + "[注意事项]\n"
            + "- 如果载具上有其他乘客，他们会被一起发射\n"
            + "- CHUNK_STORM 可能导致服务器严重卡顿";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("immersive_aircraft");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        double vx, vy, vz;
        String preset = getParam("preset").map(ConfigParam::getString).orElse("CUSTOM");
        switch (preset) {
            case "LAUNCH_UP":
                vx = 0; vy = 100000; vz = 0; break;
            case "LAUNCH_FORWARD":
                vx = 100000; vy = 0; vz = 0; break;
            case "VOID_YEET":
                vx = 0; vy = -100000; vz = 0; break;
            case "CHUNK_STORM":
                vx = 1000000; vy = 1000000; vz = 1000000; break;
            default: // CUSTOM
                vx = getParam("vx").map(ConfigParam::getFloat).orElse(0f);
                vy = getParam("vy").map(ConfigParam::getFloat).orElse(1000f);
                vz = getParam("vz").map(ConfigParam::getFloat).orElse(0f);
                break;
        }

        sendDismount(vx, vy, vz);
    }

    private void sendDismount(double fx, double fy, double fz) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_COMMAND);
            buf.writeInt(KEY_DISMOUNT);
            buf.writeDouble(fx);
            buf.writeDouble(fy);
            buf.writeDouble(fz);
        });
    }
}
