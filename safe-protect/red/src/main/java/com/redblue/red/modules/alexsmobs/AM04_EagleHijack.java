package com.redblue.red.modules.alexsmobs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AM-07: Eagle control hijack via MessageUpdateEagleControls (index 10).
 *
 * Wire: writeInt(eagleId) + writeFloat(yaw) + writeFloat(pitch)
 *       + writeBoolean(chunkLoad) + writeInt(overEntityId)
 *
 * No ownership check. Any player can control any BaldEagle.
 * directFromPlayer has a 150-block distance check internally
 * but the control input is still processed regardless.
 */
public class AM04_EagleHijack implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_EAGLE_CONTROLS = 10;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEntity("eagle_id", "白头鹰"),
        ConfigParam.ofFloat("yaw", "偏航角", 0f, -180f, 180f)
                .visibleWhen("flight_mode", "MANUAL"),
        ConfigParam.ofFloat("pitch", "俯仰角", -90f, -90f, 90f)
                .visibleWhen("flight_mode", "MANUAL"),
        ConfigParam.ofBool("chunk_load", "强制加载区块", false),
        ConfigParam.ofEntity("over_entity_id", "悬停目标实体"),
        ConfigParam.ofEnum("flight_mode", "飞行模式",
                "MANUAL", "MANUAL", "DIVE", "CIRCLE"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 2, 1, 200)
    );

    @Override public String id() { return "am04_eagle_hijack"; }
    @Override public String name() { return "白头鹰劫持"; }
    @Override public String description() {
        return "无需驯服即可控制任意白头鹰";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 用准星对准白头鹰，eagle_id 会自动填入。\n"
            + "2. 选择飞行模式：\n"
            + "   - 手动：自行设置偏航角和俯仰角。\n"
            + "   - 俯冲：白头鹰垂直向下俯冲。\n"
            + "   - 盘旋：白头鹰自动绕圈飞行。\n"
            + "3. 如需悬停在某个实体上方，用准星选取该实体填入 over_entity_id。\n"
            + "4. 设置好参数后点击「执行」或开启自动模式。\n\n"
            + "[参数说明]\n"
            + "eagle_id       — 白头鹰实体，准星选取或手动输入。\n"
            + "yaw            — 偏航角（-180~180），仅手动模式生效。\n"
            + "pitch          — 俯仰角（-90~90），仅手动模式生效。\n"
            + "chunk_load     — 是否强制加载白头鹰所在区块。\n"
            + "over_entity_id — 悬停目标实体，设置后白头鹰会飞到该实体上方。留空则不悬停。\n"
            + "flight_mode    — 飞行模式：MANUAL(手动) / DIVE(俯冲) / CIRCLE(盘旋)。\n"
            + "interval       — 自动模式下每次发送的间隔，单位为 tick。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("alexsmobs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        doControl(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(2);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doControl(mc);
    }

    private void doControl(Minecraft mc) {
        int eagleId = getParam("eagle_id").map(ConfigParam::getInt).orElse(0);
        String mode = getParam("flight_mode").map(ConfigParam::getString).orElse("MANUAL");
        boolean chunkLoad = getParam("chunk_load").map(ConfigParam::getBool).orElse(false);
        int overEntityId = getParam("over_entity_id").map(ConfigParam::getInt).orElse(-1);

        float yaw, pitch;
        switch (mode) {
            case "DIVE" -> { yaw = 0f; pitch = 90f; }
            case "CIRCLE" -> {
                float time = (mc.level.getGameTime() % 360) * 1f;
                yaw = time;
                pitch = -10f;
            }
            default -> {
                yaw = getParam("yaw").map(ConfigParam::getFloat).orElse(0f);
                pitch = getParam("pitch").map(ConfigParam::getFloat).orElse(-90f);
            }
        }

        sendControl(eagleId, yaw, pitch, chunkLoad, overEntityId);
    }

    private void sendControl(int eagleId, float yaw, float pitch,
                             boolean chunkLoad, int overEntityId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_EAGLE_CONTROLS);
            buf.writeInt(eagleId);
            buf.writeFloat(yaw);
            buf.writeFloat(pitch);
            buf.writeBoolean(chunkLoad);
            buf.writeInt(overEntityId);
        });
    }
}
