package com.redblue.red.modules.extinctionz;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * EXT-02: Backpack NBT ShareTag Item Injection (v2 — Container Click 方案)
 *
 * readShareTag() 调用 ItemStackHandler.deserializeNBT() 无校验，
 * 但实际利用需要通过容器协议让服务端接受我们伪造的 ItemStack。
 *
 * 攻击路径：
 * 1. 玩家右键打开背包 → 服务端创建 BACKPMenu，容器槽位绑定到背包的 ITEM_HANDLER
 * 2. 模块在 tick() 中检测到背包容器已打开
 * 3. 发送 ServerboundContainerClickPacket，将伪造的 ItemStack 放入容器槽位
 * 4. 服务端处理 container click 时，直接将 ItemStack 写入 ItemStackHandler
 * 5. 关闭容器后，背包 item 的 getShareTag() 序列化 capability → NBT 持久化
 *
 * 触发方式：启用模块 → 打开背包 → 自动执行（或绑定热键��容器内按）
 */
public class EXT02_BackpackNbtInject implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    /** 背包容器 Menu 类名前缀（MCreator 生成） */
    private static final String BACKP_MENU = "net.mcreator.extinction.world.inventory.BACKPMenu";
    private static final String TACTICAL_MENU = "net.mcreator.extinction.world.inventory.TacticalBackpackMenu";
    private static final String ASSAULT_MENU = "net.mcreator.extinction.world.inventory.AssaultBackpackMenu";
    private static final String BLUE_MENU = "net.mcreator.extinction.world.inventory.BLACKBLUEMenu";

    /** 捕获的完整 ItemStack（含 NBT），用于注入手持物品 */
    private volatile ItemStack capturedStack = null;

    /** 标记是否已在本次容器打开中执行过注入 */
    private boolean injectedThisSession = false;
    /** 上一 tick 是否在背包容器中 */
    private boolean wasInBackpackMenu = false;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("trigger", "触发方式", "AUTO",
                "AUTO", "HOTKEY_ONLY"),
        ConfigParam.ofItem("inject_item", "注入物品", "minecraft:diamond"),
        ConfigParam.ofAction("pick_hand", "选择手持物品(含NBT)", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                ItemStack hand = mc.player.getMainHandItem();
                if (!hand.isEmpty()) {
                    capturedStack = hand.copy();
                    ResourceLocation rl = ForgeRegistries.ITEMS.getKey(hand.getItem());
                    getParam("inject_item").ifPresent(p -> p.set(rl != null ? rl.toString() : "minecraft:air"));
                    getParam("inject_count").ifPresent(p -> p.set(hand.getCount()));
                    chat("§e[EXT02] 已捕获: " + hand.getHoverName().getString()
                            + " x" + hand.getCount());
                } else {
                    chat("§c[EXT02] 主手为空");
                }
            }
        }),
        ConfigParam.ofInt("inject_count", "注入数量", 64, 1, 64),
        ConfigParam.ofInt("inject_slot", "目标槽位", 0, 0, 43),
        ConfigParam.ofInt("num_slots", "填充槽位数", 1, 1, 44),
        ConfigParam.ofBool("fill_all", "填满所有槽位", false)
    );

    @Override public String id() { return "ext02_backpack_nbt_inject"; }
    @Override public String name() { return "背包物品注入"; }
    @Override public String description() { return "打开背包后自动向容器槽位注入任意物品"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 在模块配置中选择要注入的物品和数量\n"
            + "   可以用「选择手持物品」按钮捕获手中物品（含NBT）\n"
            + "2. 设置目标槽位和填充数量\n"
            + "3. 启用模块\n"
            + "4. 右键打开任意ExtinctionZ背包\n"
            + "5. AUTO模式：打开背包后自动注入\n"
            + "   HOTKEY_ONLY模式：打开背包后按绑定热键手动触发\n"
            + "6. 关闭背包，物品已注入\n\n"
            + "[参数说明]\n"
            + "触发方式 — AUTO(打开即注入) / HOTKEY_ONLY(需按热键)\n"
            + "注入物品 — 从列表选择或用「选择手持物品」捕获\n"
            + "注入数量 — 每个槽位的物品数量\n"
            + "目标槽位 — 从哪个槽位开始注入(0起始)\n"
            + "填充槽位数 — 连续填充几个槽位\n"
            + "填满所有槽位 — 一键填满整个背包\n\n"
            + "[注意事项]\n"
            + "必须先打开背包容器GUI才能注入\n"
            + "生存模式可用，不需要创造权限\n"
            + "4种背包均可: BACKPACK/TACTICAL/ASSAULT/BLUE";
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
        // execute() 可以在容器打开时通过热键调用
        doInject(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        boolean inBackpack = isInBackpackMenu(mc);

        // 检测：刚进入背包容器
        if (inBackpack && !wasInBackpackMenu) {
            injectedThisSession = false; // 重置
            chat("§e[EXT02] 检测到背包容器已打开");
        }

        // 检测：离开背包容器
        if (!inBackpack && wasInBackpackMenu) {
            injectedThisSession = false;
        }

        wasInBackpackMenu = inBackpack;

        // AUTO 模式：检测到背包容器且尚未注入 → 自动执行
        if (inBackpack && !injectedThisSession) {
            String trigger = getParam("trigger").map(ConfigParam::getString).orElse("AUTO");
            if ("AUTO".equals(trigger)) {
                doInject(mc);
            }
        }
    }

    /**
     * 核心注入逻辑：向当前打开的背包容器发送伪造的 ContainerClick 包
     */
    private void doInject(Minecraft mc) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) {
            chat("§c[EXT02] 请先右键打开背包容器");
            return;
        }

        if (!isBackpackMenu(menu)) {
            chat("§c[EXT02] 当前容器不是ExtinctionZ背包");
            return;
        }

        // 确定容器中背包槽位数（排除玩家物品栏的36个槽位）
        int totalSlots = menu.slots.size();
        int backpackSlots = totalSlots - 36; // 背包自身的槽位数
        if (backpackSlots <= 0) {
            chat("§c[EXT02] 容器槽位异常: " + totalSlots);
            return;
        }

        // 构建注入物品
        ItemStack injectStack = buildInjectStack();
        if (injectStack.isEmpty()) {
            chat("§c[EXT02] 注入物品为空，请检查配置");
            return;
        }

        int startSlot = getParam("inject_slot").map(ConfigParam::getInt).orElse(0);
        boolean fillAll = getParam("fill_all").map(ConfigParam::getBool).orElse(false);
        int numSlots = fillAll ? backpackSlots : getParam("num_slots").map(ConfigParam::getInt).orElse(1);

        int injected = 0;
        int containerId = menu.containerId;
        int stateId = menu.getStateId();

        for (int i = 0; i < numSlots; i++) {
            int targetSlot = (startSlot + i) % backpackSlots;
            if (targetSlot >= menu.slots.size()) continue;

            ItemStack toSend = injectStack.copy();

            // 构造 ServerboundContainerClickPacket
            // PICKUP click type (0) with button 0 = left click
            // changedSlots 记录我们改变的槽位
            Int2ObjectMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
            changedSlots.put(targetSlot, toSend);

            ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                    containerId, stateId, targetSlot,
                    0, // button (left click)
                    ClickType.PICKUP,
                    toSend, // carried item
                    changedSlots
            );

            mc.player.connection.send(packet);

            // 同步客户端侧的槽位（防止服务端 resync 覆盖）
            if (targetSlot < menu.slots.size()) {
                menu.slots.get(targetSlot).set(toSend);
            }

            injected++;
        }

        injectedThisSession = true;
        chat("§a[EXT02] 已注入 " + injected + " 个槽位 ("
                + injectStack.getHoverName().getString() + " x" + injectStack.getCount() + ")");
        chat("§e[EXT02] 关闭背包使物品持久化");
    }

    /**
     * 构建要注入的 ItemStack
     */
    private ItemStack buildInjectStack() {
        int count = getParam("inject_count").map(ConfigParam::getInt).orElse(64);

        // 优先使用捕获的完整 ItemStack（含 NBT）
        if (capturedStack != null && !capturedStack.isEmpty()) {
            ItemStack stack = capturedStack.copy();
            stack.setCount(count);
            return stack;
        }

        // 回退：从 ofItem 选择器的 registry name 创建
        String regName = getParam("inject_item").map(ConfigParam::getString)
                .orElse("minecraft:diamond");
        ResourceLocation rl = new ResourceLocation(regName);
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item, count);
    }

    /**
     * 检测当前是否在背包容器中
     */
    private boolean isInBackpackMenu(Minecraft mc) {
        if (mc.player == null) return false;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu == mc.player.inventoryMenu) return false;
        return isBackpackMenu(menu);
    }

    /**
     * 检测 Menu 是否为 ExtinctionZ 背包容器
     */
    private boolean isBackpackMenu(AbstractContainerMenu menu) {
        String className = menu.getClass().getName();
        return className.equals(BACKP_MENU)
            || className.equals(TACTICAL_MENU)
            || className.equals(ASSAULT_MENU)
            || className.equals(BLUE_MENU);
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
