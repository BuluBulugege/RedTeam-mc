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
 * FTQ-05: SyncStructuresRequestMessage - info leak.
 *
 * Empty payload, no permission check, no rate limit.
 * Server responds with SyncStructuresResponseMessage containing
 * all generated structure locations in the world.
 *
 * Useful for reconnaissance: locate strongholds, temples, etc.
 */
public class FTQ05_StructureLeak implements AttackModule {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");
    private static final String MSG_CLASS =
        "dev.ftb.mods.ftbquests.net.SyncStructuresRequestMessage";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("interval", "请求间隔(tick)", 100, 20, 6000)
    );

    private long lastTick = 0;

    @Override public String id() { return "ftq05_structure_leak"; }
    @Override public String name() { return "结构信息泄露"; }
    @Override public String description() {
        return "获取服务端任务结构的隐藏信息";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 点击执行发送单次请求\n"
             + "2. 启用自动模式定期请求更新\n"
             + "3. 查看客户端日志获取返回数据\n\n"
             + "[参数说明]\n"
             + "- 请求间隔: 自动模式下每次请求的tick间隔\n\n"
             + "[注意事项]\n"
             + "- 返回数据包含世界中所有已生成的结构位置\n"
             + "- 结果输出在客户端日志中, 需查看日志文件";
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
        sendStructureRequest();
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(100);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        sendStructureRequest();
    }

    private void sendStructureRequest() {
        try {
            Class<?> clz = Class.forName(MSG_CLASS);
            Constructor<?> ctor = clz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object msg = ctor.newInstance();
            Method send = msg.getClass().getMethod("sendToServer");
            send.invoke(msg);
        } catch (Exception e) {
            LOG.error("[FTQ05] Failed to send SyncStructuresRequestMessage", e);
        }
    }
}
