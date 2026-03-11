package com.redblue.red.modules.extinctionz;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.util.List;
import java.util.Optional;

/**
 * EXT-01: Remote Container Access
 *
 * Exploits the stillValid() bypass in all 20 ExtinctionZ Menu classes.
 * When bound == false (no BlockEntity/Entity/Item found), stillValid() returns true
 * unconditionally with no distance check.
 *
 * Attack vector: Open any ExtinctionZ container, walk away to any distance,
 * suppress the close packet, and continue interacting with the container remotely.
 *
 * This module suppresses ServerboundContainerClosePacket and periodically sends
 * a no-op container click to keep the session alive.
 */
public class EXT01_RemoteContainer implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private long lastKeepAlive = 0;
    private int capturedContainerId = -1;
    private boolean suppressClose = false;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("suppress_close", "阻止关闭容器",
                "开启后，按ESC不会发送关闭容器包，保持远程会话", false),
        ConfigParam.ofInt("keepalive_interval", "保活间隔(tick)", 100, 20, 600),
        ConfigParam.ofInt("steal_slot", "窃取槽位ID", 0, 0, 45),
        ConfigParam.ofAction("capture", "捕获当前容器ID", this::captureCurrentContainer),
        ConfigParam.ofAction("steal_one", "从指定槽位shift-click取出", this::stealFromSlot)
    );

    @Override public String id() { return "ext01_remote_container"; }
    @Override public String name() { return "远程容器访问"; }
    @Override public String description() { return "绕过stillValid距离校验，远程操作ExtinctionZ容器"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 正常走到ExtinctionZ的容器方块旁(售货机/柜子/冰箱等)并右键打开\n"
            + "2. 点击「捕获当前容器ID」按钮记录当前容器会话\n"
            + "3. 开启「阻止关闭容器」，然后按ESC关闭GUI(包不会发出)\n"
            + "4. 走到任意距离，启用模块，模块会自动发送保活包维持会话\n"
            + "5. 使用「从指定槽位shift-click取出」按钮远程窃取物品\n\n"
            + "[原理]\n"
            + "所有ExtinctionZ Menu的stillValid()在bound==false时直接返回true\n"
            + "即使bound==true且绑定BlockEntity，距离校验也依赖原版m_38889_\n"
            + "但如果BlockEntity在打开后被破坏，bound仍为true但校验可能失效\n\n"
            + "[注意]\n"
            + "需要服务器安装 extinction mod";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("extinction");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        captureCurrentContainer();
        LOGGER.info("[EXT01] 手动执行: 已捕获容器ID={}", capturedContainerId);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        int interval = getParam("keepalive_interval").map(ConfigParam::getInt).orElse(100);
        long now = mc.level.getGameTime();
        if (now - lastKeepAlive < interval) return;
        lastKeepAlive = now;

        if (capturedContainerId < 0) return;

        // Send a no-op container click to keep the session alive
        // ClickType.PICKUP with button=0, slot=-999 (outside window) = drop nothing = no-op
        sendKeepAlive(mc);
    }

    private void captureCurrentContainer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu != null && menu != mc.player.inventoryMenu) {
            capturedContainerId = menu.containerId;
            LOGGER.info("[EXT01] 捕获容器ID: {}", capturedContainerId);
        } else {
            LOGGER.warn("[EXT01] 当前没有打开的容器");
        }
    }

    private void stealFromSlot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        if (capturedContainerId < 0) {
            LOGGER.warn("[EXT01] 未捕获容器ID，请先打开容器并捕获");
            return;
        }

        int slot = getParam("steal_slot").map(ConfigParam::getInt).orElse(0);

        // Send shift-click (QUICK_MOVE) to move item from container slot to player inventory
        mc.player.connection.send(new ServerboundContainerClickPacket(
            capturedContainerId,
            0, // stateId - server will correct if wrong
            slot,
            0, // button
            ClickType.QUICK_MOVE,
            ItemStack.EMPTY,
            Int2ObjectMaps.emptyMap()
        ));
        LOGGER.info("[EXT01] 发送shift-click: containerId={}, slot={}", capturedContainerId, slot);
    }

    private void sendKeepAlive(Minecraft mc) {
        if (mc.player == null || mc.player.connection == null) return;

        // Click outside the window (slot -999) with empty hand = no-op but keeps session alive
        mc.player.connection.send(new ServerboundContainerClickPacket(
            capturedContainerId,
            0,
            -999,
            0,
            ClickType.PICKUP,
            ItemStack.EMPTY,
            Int2ObjectMaps.emptyMap()
        ));
    }

    public boolean isSuppressClose() {
        return getParam("suppress_close").map(ConfigParam::getBool).orElse(false) && isEnabled();
    }

    public int getCapturedContainerId() {
        return capturedContainerId;
    }
}
