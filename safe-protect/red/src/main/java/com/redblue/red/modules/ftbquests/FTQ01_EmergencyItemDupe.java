package com.redblue.red.modules.ftbquests;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FTQ-01: GetEmergencyItemsMessage has ZERO server-side checks.
 *
 * The client UI has a 300s cooldown (EmergencyItemsScreen.emergencyItemsCooldown),
 * but the server handle() simply iterates getEmergencyItems() and gives them all
 * to the player with no cooldown, no permission, no rate limit.
 *
 * Attack: construct GetEmergencyItemsMessage (empty payload) via reflection
 * and call sendToServer() repeatedly.
 */
public class FTQ01_EmergencyItemDupe implements AttackModule {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");
    private static final String MSG_CLASS = "dev.ftb.mods.ftbquests.net.GetEmergencyItemsMessage";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("burst_count", "单次执行发包数量", 20, 1, 200),
        ConfigParam.ofInt("interval", "自动发包间隔(tick)", 1, 1, 200),
        ConfigParam.ofInt("auto_burst", "自动模式每tick发包数", 1, 1, 50)
    );

    private long lastTick = 0;

    @Override public String id() { return "ftq01_emergency_dupe"; }
    @Override public String name() { return "紧急物品复制"; }
    @Override public String description() { return "利用紧急物品机制复制任意物品"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 点击执行进行单次批量发包\n"
             + "2. 启用并设置间隔进行持续刷取\n"
             + "3. 单次执行发包数量控制每次点击发送的数据包数\n"
             + "4. 自动模式每tick发包数控制启用后每tick发送的数据包数\n\n"
             + "[参数说明]\n"
             + "- 单次执行发包数量: 每次点击执行时发送的请求数量\n"
             + "- 自动发包间隔: 自动模式下每次发包的tick间隔\n"
             + "- 自动模式每tick发包数: 自动模式下每次触发发送的数量\n\n"
             + "[注意事项]\n"
             + "- 服务端需配置emergency_items才有效\n"
             + "- 整合包中常见配置为食物、工具等物品";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("ftbquests");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        int count = getParam("burst_count").map(ConfigParam::getInt).orElse(20);
        for (int i = 0; i < count; i++) {
            sendEmergencyItemsPacket();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        int burst = getParam("auto_burst").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < burst; i++) {
            sendEmergencyItemsPacket();
        }
    }

    private void sendEmergencyItemsPacket() {
        try {
            Class<?> clz = Class.forName(MSG_CLASS);
            Constructor<?> ctor = clz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object msg = ctor.newInstance();
            Method send = msg.getClass().getMethod("sendToServer");
            send.invoke(msg);
        } catch (Exception e) {
            LOG.error("[FTQ01] Failed to send GetEmergencyItemsMessage", e);
        }
    }
}
