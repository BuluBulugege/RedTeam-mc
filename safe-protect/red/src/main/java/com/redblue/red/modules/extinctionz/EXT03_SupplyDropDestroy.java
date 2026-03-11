package com.redblue.red.modules.extinctionz;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.core.Direction;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * EXT-03: SupplyDrop Item Destruction
 *
 * SupplyDropBlock has no onRemoved (m_6810_) override, so breaking it
 * permanently destroys all 18 slots of stored items.
 * Compare with VendingMachineBlock which correctly calls Containers.m_19002_.
 *
 * Attack: Break a SupplyDrop block to permanently delete all items inside.
 * This module automates finding and breaking nearby SupplyDrop blocks.
 */
public class EXT03_SupplyDropDestroy implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBlock("target_pos", "目标SupplyDrop坐标"),
        ConfigParam.ofInt("scan_radius", "扫描半径", 8, 1, 32),
        ConfigParam.ofAction("scan", "扫描附近SupplyDrop", this::scanNearby)
    );

    @Override public String id() { return "ext03_supplydrop_destroy"; }
    @Override public String name() { return "SupplyDrop物品销毁"; }
    @Override public String description() { return "破坏SupplyDrop方块使内容物永久消失"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 点击「扫描附近SupplyDrop」找到附近的SupplyDrop方块\n"
            + "2. 或手动用十字准星瞄准SupplyDrop方块设置坐标\n"
            + "3. 点击Execute开始破坏目标方块\n\n"
            + "[原理]\n"
            + "SupplyDropBlock没有覆写onRemoved(m_6810_)方法\n"
            + "其他容器方块(VendingMachine/Shelf等)都正确调用了\n"
            + "Containers.m_19002_来掉落内容物\n"
            + "SupplyDrop被破坏时，18个槽位的物品全部永久消失\n\n"
            + "[注意]\n"
            + "这是一个破坏性操作，物品无法恢复\n"
            + "需要能够破坏方块的权限(生存模式即可)";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("extinction");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        long posLong = getParam("target_pos").map(ConfigParam::getLong).orElse(0L);
        if (posLong == 0L) {
            // Try to use the block the player is looking at
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                BlockPos lookPos = bhr.getBlockPos();
                String blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getKey(mc.level.getBlockState(lookPos).getBlock()).toString();
                if (blockId.contains("supply_drop")) {
                    startBreaking(mc, lookPos);
                    return;
                }
            }
            LOGGER.warn("[EXT03] 未设置目标坐标，且准星未对准SupplyDrop");
            return;
        }

        BlockPos target = BlockPos.of(posLong);
        startBreaking(mc, target);
    }

    private void startBreaking(Minecraft mc, BlockPos pos) {
        if (mc.gameMode == null) return;

        // Start breaking the block - vanilla mechanic handles the rest
        mc.gameMode.startDestroyBlock(pos, Direction.UP);
        LOGGER.info("[EXT03] 开始破坏SupplyDrop: {}", pos);
    }

    private void scanNearby() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int radius = getParam("scan_radius").map(ConfigParam::getInt).orElse(8);
        BlockPos playerPos = mc.player.blockPosition();
        int found = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos check = playerPos.offset(dx, dy, dz);
                    var blockId = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                            .getKey(mc.level.getBlockState(check).getBlock());
                    if (blockId != null && blockId.toString().contains("supply_drop")) {
                        // Set as target
                        getParam("target_pos").ifPresent(p -> p.set(check.asLong()));
                        found++;
                        LOGGER.info("[EXT03] 发现SupplyDrop: {} ({})", check, blockId);
                    }
                }
            }
        }

        if (found == 0) {
            LOGGER.info("[EXT03] 半径{}内未发现SupplyDrop方块", radius);
        } else {
            LOGGER.info("[EXT03] 共发现{}个SupplyDrop，已设置最后一个为目标", found);
        }
    }
}
