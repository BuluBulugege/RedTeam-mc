package com.redblue.red.modules.tacz;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * TACZ-01: 配件类型混淆复制
 *
 * 服务端 ClientMessageRefitGun.handle() 使用客户端提供的 AttachmentType
 * 查找旧配件 (getAttachment(gun, type))，但 installAttachment 使用物品
 * 自身的实际类型。发送错误的 type 导致旧配件查找失败（返回 EMPTY），
 * 而新配件仍被安装。配合 UnloadAttachment 的同类问题可实现配件复制。
 *
 * AttachmentLock 仅在客户端 RefitKey 中校验，服务端 handler 不检查。
 */
public class TACZ01_AttachmentDupe implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("TACZ01");

    private static final String NETWORK_HANDLER = "com.tacz.guns.network.NetworkHandler";
    private static final String CHANNEL_FIELD = "CHANNEL";
    private static final String REFIT_MSG = "com.tacz.guns.network.message.ClientMessageRefitGun";
    private static final String UNLOAD_MSG = "com.tacz.guns.network.message.ClientMessageUnloadAttachment";
    private static final String ATTACHMENT_TYPE = "com.tacz.guns.api.item.attachment.AttachmentType";

    private static boolean reflectionResolved = false;
    private static Constructor<?> refitCtor;    // (int, int, AttachmentType)
    private static Constructor<?> unloadCtor;   // (int, AttachmentType)
    private static Class<?> attachTypeClass;
    private static Method valueOfMethod;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofEnum("action", "操作",
                    "REFIT", "REFIT", "UNLOAD", "REFIT_CONFUSED"),
            ConfigParam.ofInt("gun_slot", "枪械栏位", 0, 0, 8),
            ConfigParam.ofInt("attachment_slot", "配件栏位", 1, 0, 35)
                    .visibleWhen("action", "REFIT", "REFIT_CONFUSED"),
            ConfigParam.ofEnum("send_type", "发包类型",
                    "SCOPE", "SCOPE", "MUZZLE", "STOCK", "GRIP", "LASER", "EXTENDED_MAG"),
            ConfigParam.ofEnum("real_type", "实际类型",
                    "SCOPE", "SCOPE", "MUZZLE", "STOCK", "GRIP", "LASER", "EXTENDED_MAG")
                    .visibleWhen("action", "REFIT_CONFUSED")
    );

    @Override public String id() { return "tacz01_attachment_dupe"; }
    @Override public String name() { return "配件类型混淆"; }
    @Override public String description() { return "利用 Refit 包的类型混淆漏洞操作枪械配件"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择操作模式:\n"
            + "   REFIT: 正常安装配件（用于对照测试）\n"
            + "   UNLOAD: 卸载指定类型的配件\n"
            + "   REFIT_CONFUSED: 类型混淆安装（发包类型≠实际类型）\n"
            + "2. 设置枪械栏位（快捷栏0-8）和配件栏位\n"
            + "3. 设置发包类型（服务端用此查找旧配件）\n"
            + "4. REFIT_CONFUSED 模式下设置实际类型用于对比\n\n"
            + "[原理]\n"
            + "RefitGun handler 用客户端提供的 AttachmentType 调用\n"
            + "getAttachment(gun, type) 查找旧配件，但 installAttachment\n"
            + "根据物品自身类型决定安装槽位。发送错误 type 导致旧配件\n"
            + "查找返回 EMPTY，新配件仍被安装，旧配件不被归还。\n"
            + "AttachmentLock 仅客户端校验，服务端不检查。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("tacz");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        resolveReflection();
        if (refitCtor == null || unloadCtor == null) {
            chat(mc, "\u00a7c[TACZ01] 反射解析失败，TACZ 未加载？");
            return;
        }

        String action = getParam("action").map(ConfigParam::getString).orElse("REFIT");
        int gunSlot = getParam("gun_slot").map(ConfigParam::getInt).orElse(0);
        String sendType = getParam("send_type").map(ConfigParam::getString).orElse("SCOPE");

        switch (action) {
            case "REFIT" -> doRefit(mc, gunSlot, sendType, sendType);
            case "UNLOAD" -> doUnload(mc, gunSlot, sendType);
            case "REFIT_CONFUSED" -> {
                String realType = getParam("real_type").map(ConfigParam::getString).orElse("SCOPE");
                doRefit(mc, gunSlot, sendType, realType);
            }
        }
    }

    private void doRefit(Minecraft mc, int gunSlot, String sendType, String realType) {
        int attachSlot = getParam("attachment_slot").map(ConfigParam::getInt).orElse(1);
        try {
            Object typeEnum = getAttachmentType(sendType);
            if (typeEnum == null) {
                chat(mc, "\u00a7c[TACZ01] 无效类型: " + sendType);
                return;
            }
            Object msg = refitCtor.newInstance(attachSlot, gunSlot, typeEnum);
            boolean ok = PacketForge.sendViaChannel(NETWORK_HANDLER, CHANNEL_FIELD, msg);
            if (ok) {
                String label = sendType.equals(realType)
                        ? "REFIT(" + sendType + ")"
                        : "CONFUSED(send=" + sendType + ", real=" + realType + ")";
                chat(mc, "\u00a7a[TACZ01] " + label
                        + " slot=" + attachSlot + " gun=" + gunSlot);
            } else {
                chat(mc, "\u00a7c[TACZ01] 发送失败");
            }
        } catch (Exception e) {
            LOGGER.error("[TACZ01] Refit failed", e);
            chat(mc, "\u00a7c[TACZ01] 异常: " + e.getMessage());
        }
    }

    private void doUnload(Minecraft mc, int gunSlot, String sendType) {
        try {
            Object typeEnum = getAttachmentType(sendType);
            if (typeEnum == null) {
                chat(mc, "\u00a7c[TACZ01] 无效类型: " + sendType);
                return;
            }
            Object msg = unloadCtor.newInstance(gunSlot, typeEnum);
            boolean ok = PacketForge.sendViaChannel(NETWORK_HANDLER, CHANNEL_FIELD, msg);
            if (ok) {
                chat(mc, "\u00a7a[TACZ01] UNLOAD(" + sendType + ") gun=" + gunSlot);
            } else {
                chat(mc, "\u00a7c[TACZ01] 发送失败");
            }
        } catch (Exception e) {
            LOGGER.error("[TACZ01] Unload failed", e);
            chat(mc, "\u00a7c[TACZ01] 异常: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object getAttachmentType(String name) {
        try {
            return Enum.valueOf((Class) attachTypeClass, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void resolveReflection() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            attachTypeClass = Class.forName(ATTACHMENT_TYPE);

            Class<?> refitClass = Class.forName(REFIT_MSG);
            refitCtor = refitClass.getDeclaredConstructor(
                    int.class, int.class, attachTypeClass);
            refitCtor.setAccessible(true);

            Class<?> unloadClass = Class.forName(UNLOAD_MSG);
            unloadCtor = unloadClass.getDeclaredConstructor(
                    int.class, attachTypeClass);
            unloadCtor.setAccessible(true);

            LOGGER.info("[TACZ01] Reflection resolved");
        } catch (Exception e) {
            LOGGER.error("[TACZ01] Reflection failed", e);
        }
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
