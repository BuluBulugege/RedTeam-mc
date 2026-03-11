package com.redblue.red.modules.extinctionz;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * EXT-04: Backpack boundItemMatcher Reference Bypass
 *
 * All 4 backpack Menus use Java == reference comparison for boundItemMatcher:
 *   this.boundItemMatcher = () -> itemstack == (hand == 0 ? entity.m_21205_() : entity.m_21206_());
 *
 * When the player switches hotbar slots, the ItemStack reference changes,
 * causing boundItemMatcher.get() to return false, which makes stillValid()
 * return false. The server then force-closes the container.
 *
 * In m_6877_ (removed), when bound == true, the item return logic is SKIPPED.
 * This means any items on the cursor (carried item) are lost permanently.
 *
 * Attack: Open backpack -> pick up items onto cursor -> switch hotbar slot
 * -> server closes container -> cursor items vanish.
 */
public class EXT04_BackpackRefBypass implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private boolean armed = false;
    private int armedTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofInt("pickup_slot", "拾取槽位", 0, 0, 43),
        ConfigParam.ofInt("delay_ticks", "切换延迟(tick)", 2, 1, 20),
        ConfigParam.ofEnum("target_hand", "背包所在手", "MAIN", "MAIN", "OFF")
    );

    @Override public String id() { return "ext04_backpack_ref_bypass"; }
    @Override public String name() { return "背包引用绕过"; }
    @Override public String description() { return "利用==引用比较绕过背包验证，导致物品丢失"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持ExtinctionZ背包物品并右键打开\n"
            + "2. 设置要拾取到光标的槽位ID\n"
            + "3. 点击Execute: 模块会先点击槽位拾取物品到光标\n"
            + "4. 然后在延迟后切换快捷栏槽位\n"
            + "5. 服务端检测到引用不匹配，强制关闭容器\n"
            + "6. 由于bound==true，m_6877_不执行物品归还\n"
            + "7. 光标上的物品永久消失\n\n"
            + "[原理]\n"
            + "boundItemMatcher使用==引用比较而非ItemStack.matches()\n"
            + "切换快捷栏后，getMainHandItem()返回不同的ItemStack对象\n"
            + "m_6877_(removed)中: if(!bound) 才归还物品\n"
            + "但背包打开时bound==true，所以不归还\n\n"
            + "[影响]\n"
            + "可用于销毁目标玩家背包中的物品(社工诱导对方操作)\n"
            + "或作为grief手段破坏物品";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("extinction");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            LOGGER.warn("[EXT04] 请先打开背包容器");
            return;
        }

        int slot = getParam("pickup_slot").map(ConfigParam::getInt).orElse(0);

        // Step 1: Pick up item from the specified slot onto cursor
        mc.player.connection.send(new ServerboundContainerClickPacket(
            menu.containerId,
            menu.getStateId(),
            slot,
            0, // left click
            ClickType.PICKUP,
            ItemStack.EMPTY,
            new Int2ObjectOpenHashMap<>()
        ));

        LOGGER.info("[EXT04] 已拾取槽位{}的物品到光标，等待切换快捷栏...", slot);

        // Arm the tick handler to switch hotbar after delay
        armed = true;
        armedTick = 0;
    }

    @Override
    public void tick(Minecraft mc) {
        if (!armed || mc.player == null) return;

        int delay = getParam("delay_ticks").map(ConfigParam::getInt).orElse(2);
        armedTick++;

        if (armedTick >= delay) {
            armed = false;
            armedTick = 0;

            // Step 2: Switch hotbar slot to invalidate the ItemStack reference
            int currentSlot = mc.player.getInventory().selected;
            int newSlot = (currentSlot + 1) % 9;
            mc.player.getInventory().selected = newSlot;

            // Send the hotbar change to server
            mc.player.connection.send(
                new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(newSlot)
            );

            LOGGER.info("[EXT04] 已切换快捷栏 {} -> {}，服务端将强制关闭容器",
                    currentSlot, newSlot);
            LOGGER.info("[EXT04] 光标上的物品将因bound==true而不被归还");
        }
    }
}
