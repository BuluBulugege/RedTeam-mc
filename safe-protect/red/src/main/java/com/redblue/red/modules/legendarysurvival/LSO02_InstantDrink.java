package com.redblue.red.modules.legendarysurvival;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LSO-02: Instant Hydration (DrinkBlockFluidMessage rate abuse)
 *
 * Vulnerability: No server-side cooldown on DrinkBlockFluidMessage.
 * Server does ray-trace check (player must face water within BLOCK_REACH/2),
 * but accepts unlimited packets per tick.
 *
 * Approach: Reflection call to DrinkBlockFluidMessage.sendToServer()
 * which is a static no-arg method that constructs and sends the empty message.
 */
public class LSO02_InstantDrink implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private static final String MSG_CLASS =
            "sfiomn.legendarysurvivaloverhaul.network.packets.DrinkBlockFluidMessage";

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofInt("burst_count", "单次发包数", 20, 1, 100),
            ConfigParam.ofInt("interval", "自动间隔(tick)", 5, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lso02_instant_drink"; }
    @Override public String name() { return "瞬间饮水"; }
    @Override public String description() { return "高频发送饮水包瞬间灌满口渴值(需面朝水源)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
                + "1. 面朝水源方块(在方块交互距离/2范围内)\n"
                + "2. 设置burst_count(每轮发包数,20次足以灌满)\n"
                + "3. 点击执行或启用自动模式\n\n"
                + "[原理]\n"
                + "DrinkBlockFluidMessage是空包体\n"
                + "服务端无冷却/速率限制\n"
                + "每次发包服务端执行一次takeDrink\n"
                + "高频发送可瞬间灌满hydration值\n\n"
                + "[限制]\n"
                + "必须面朝水源方块,服务端会做射线检测";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("legendarysurvivaloverhaul");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int count = getParam("burst_count").map(ConfigParam::getInt).orElse(20);
        sendBurst(count);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        int count = getParam("burst_count").map(ConfigParam::getInt).orElse(20);
        sendBurst(count);
    }

    private void sendBurst(int count) {
        for (int i = 0; i < count; i++) {
            sendDrinkPacket();
        }
    }

    /**
     * Call DrinkBlockFluidMessage.sendToServer() via reflection.
     * It's a static no-arg method that constructs the empty message
     * and sends via NetworkHandler.INSTANCE.sendToServer().
     */
    private void sendDrinkPacket() {
        try {
            Class<?> msgClz = Class.forName(MSG_CLASS);
            var method = msgClz.getDeclaredMethod("sendToServer");
            method.setAccessible(true);
            method.invoke(null);
        } catch (Exception e) {
            LOGGER.error("LSO02 sendDrinkPacket failed", e);
        }
    }
}
