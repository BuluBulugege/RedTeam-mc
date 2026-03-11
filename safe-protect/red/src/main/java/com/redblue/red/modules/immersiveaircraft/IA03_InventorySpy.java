package com.redblue.red.modules.immersiveaircraft;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * IA-03: 载具物品栏窃取 — 反射直读方案
 *
 * 新方案: 绕过发包，直接用反射读取客户端实体的物品栏数据。
 *
 * 原方案通过 RequestInventory 发包让服务端返回物品栏内容，
 * 但 cobalt 的 NetworkHandler.sendToServer() 在反射调用时有序列化问题。
 *
 * 新方案:
 *   1. 用 mc.level.getEntity(id) 或 mc.player.getVehicle() 获取实体
 *   2. 反射调用 InventoryVehicleEntity.getInventory() 获取 SparseSimpleInventory
 *   3. SparseSimpleInventory extends SimpleContainer，直接用 getItem(slot) 遍历
 *   4. 完全不需要发包，直接在客户端读取已同步的实体数据
 *
 * 注意: 客户端只能看到已同步到本地的实体数据。
 * 对于骑乘/准星/附近的载具，数据通常已同步。
 */
public class IA03_InventorySpy implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "模式", "OPEN_GUI",
                "OPEN_GUI", "SINGLE", "NEARBY"),
        ConfigParam.ofEntity("target_entity", "目标载具实体ID")
                .visibleWhen("mode", "SINGLE"),
        ConfigParam.ofInt("scan_radius", "扫描半径(格)", 64, 1, 128)
                .visibleWhen("mode", "NEARBY")
    );

    @Override public String id() { return "ia03_inventory_spy"; }
    @Override public String name() { return "载具物品栏窃取"; }

    @Override
    public String description() {
        return "反射直读载具物品栏，无需发包";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. OPEN_GUI 模式: 准星对准载具或骑乘载具，点击「执行」\n"
            + "   反射读取物品栏内容，在聊天栏显示\n"
            + "2. SINGLE 模式: 手动输入实体ID，点击「执行」\n"
            + "   用 mc.level.getEntity(id) 获取实体后读取\n"
            + "3. NEARBY 模式: 设置扫描半径，点击「执行」\n"
            + "   扫描附近所有 InventoryVehicleEntity 并读取物品栏\n\n"
            + "[原理]\n"
            + "- 直接反射调用 getInventory() 读取客户端已同步的数据\n"
            + "- 不需要发包，不受 cobalt 序列化问题影响\n"
            + "- 客户端能看到的实体数据取决于服务端同步范围";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("immersive_aircraft");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        String mode = getParam("mode").map(ConfigParam::getString).orElse("OPEN_GUI");
        switch (mode) {
            case "OPEN_GUI":
                executeOpenGui(mc);
                break;
            case "SINGLE":
                executeSingle(mc);
                break;
            case "NEARBY":
                executeNearby(mc);
                break;
        }
    }

    // ── OPEN_GUI: 准星/骑乘目标 → 反射打开载具容器GUI ──

    private void executeOpenGui(Minecraft mc) {
        Entity target = mc.crosshairPickEntity;
        if (target == null) target = mc.player.getVehicle();
        if (target == null) {
            chat("\u00a7c[IA03] 请用准星对准载具，或骑乘载具后执行");
            return;
        }

        if (!isInventoryVehicle(target)) {
            chat("\u00a7c[IA03] " + target.getClass().getSimpleName()
                    + " 不是 InventoryVehicleEntity");
            return;
        }

        chat("\u00a7e[IA03] 尝试打开载具 #" + target.getId()
                + " (" + target.getClass().getSimpleName() + ") 的容器GUI...");
        if (!tryOpenVehicleScreen(mc, target)) {
            chat("\u00a7e[IA03] GUI打开失败，回退到聊天栏显示:");
            readAndDisplayInventory(target);
        }
    }

    // ── SINGLE: 手动指定实体ID → 反射读取 ──

    private void executeSingle(Minecraft mc) {
        int entityId = getParam("target_entity").map(ConfigParam::getInt).orElse(0);
        if (entityId <= 0) {
            chat("\u00a7c[IA03] 请输入有效的实体ID (> 0)");
            return;
        }

        Entity target = mc.level.getEntity(entityId);
        if (target == null) {
            chat("\u00a7c[IA03] 实体 #" + entityId + " 不存在或未加载");
            return;
        }

        if (!isInventoryVehicle(target)) {
            chat("\u00a7c[IA03] 实体 #" + entityId + " ("
                    + target.getClass().getSimpleName()
                    + ") 不是 InventoryVehicleEntity");
            return;
        }

        chat("\u00a7e[IA03] 尝试打开实体 #" + entityId
                + " (" + target.getClass().getSimpleName() + ") 的容器GUI...");
        if (!tryOpenVehicleScreen(mc, target)) {
            chat("\u00a7e[IA03] GUI打开失败，回退到聊天栏显示:");
            readAndDisplayInventory(target);
        }
    }

    // ── NEARBY: 范围扫描 → 找到所有 InventoryVehicleEntity ──

    private void executeNearby(Minecraft mc) {
        int radius = getParam("scan_radius").map(ConfigParam::getInt).orElse(64);
        AABB box = mc.player.getBoundingBox().inflate(radius);
        List<Entity> entities = mc.level.getEntities(mc.player, box);

        List<Entity> vehicles = new ArrayList<>();
        for (Entity e : entities) {
            if (isInventoryVehicle(e)) {
                vehicles.add(e);
            }
        }

        if (vehicles.isEmpty()) {
            chat("\u00a7c[IA03] 半径 " + radius + " 格内未发现 InventoryVehicleEntity");
            return;
        }

        chat("\u00a7a[IA03] 发现 " + vehicles.size() + " 个载具:");
        for (Entity v : vehicles) {
            chat("\u00a7e--- 载具 #" + v.getId()
                    + " (" + v.getClass().getSimpleName() + ") ---");
            if (!tryOpenVehicleScreen(mc, v)) {
                chat("\u00a7e[IA03] GUI打开失败，回退到聊天栏显示:");
                readAndDisplayInventory(v);
            }
        }
    }

    // ── 核心: 反射打开载具容器GUI ──

    private static final String VEHICLE_SCREEN_HANDLER =
            "immersive_aircraft.screen.VehicleScreenHandler";
    private static final String VEHICLE_SCREEN =
            "immersive_aircraft.client.gui.VehicleScreen";
    private static final String INVENTORY_VEHICLE_ENTITY =
            "immersive_aircraft.entity.InventoryVehicleEntity";

    /**
     * 反射构造 VehicleScreenHandler + VehicleScreen 并打开。
     * VehicleScreenHandler(int syncId, Inventory playerInv, InventoryVehicleEntity vehicle)
     * VehicleScreen(VehicleScreenHandler handler, Inventory playerInv, Component title)
     */
    private boolean tryOpenVehicleScreen(Minecraft mc, Entity vehicle) {
        try {
            Class<?> handlerClz = Class.forName(VEHICLE_SCREEN_HANDLER);
            Class<?> screenClz = Class.forName(VEHICLE_SCREEN);
            Class<?> vehicleClz = Class.forName(INVENTORY_VEHICLE_ENTITY);

            // 构造 VehicleScreenHandler(int, Inventory, InventoryVehicleEntity)
            Constructor<?> handlerCtor = handlerClz.getDeclaredConstructor(
                    int.class, Inventory.class, vehicleClz);
            handlerCtor.setAccessible(true);

            Inventory playerInv = mc.player.getInventory();
            int syncId = mc.player.containerMenu.containerId + 1; // 客户端侧使用下一个ID
            Object handler = handlerCtor.newInstance(syncId, playerInv, vehicle);

            // 构造 VehicleScreen(VehicleScreenHandler, Inventory, Component)
            Constructor<?> screenCtor = screenClz.getDeclaredConstructor(
                    handlerClz, Inventory.class, Component.class);
            screenCtor.setAccessible(true);

            Component title = Component.literal(vehicle.getClass().getSimpleName());
            Screen screen = (Screen) screenCtor.newInstance(handler, playerInv, title);

            mc.setScreen(screen);
            chat("\u00a7a[IA03] 容器GUI已打开");
            return true;
        } catch (Exception e) {
            LOGGER.error("[IA03] 打开容器GUI失败", e);
            chat("\u00a7c[IA03] GUI失败: " + e.getMessage());
            return false;
        }
    }

    // ── 回退: 反射读取物品栏并在聊天栏显示 ──

    /**
     * 反射调用 entity.getInventory() 获取 SparseSimpleInventory，
     * 然后用 getContainerSize() + getItem(slot) 遍历所有槽位。
     *
     * SparseSimpleInventory extends SimpleContainer extends SimpleContainer
     * SimpleContainer 实现了 Container 接口:
     *   - int getContainerSize()  -> m_6643_()
     *   - ItemStack getItem(int)  -> m_8020_(int)
     */
    private void readAndDisplayInventory(Entity vehicle) {
        try {
            // Step 1: 反射调用 getInventory()
            Object inventory = reflectGetInventory(vehicle);
            if (inventory == null) {
                chat("\u00a7c[IA03] getInventory() 返回 null");
                return;
            }

            chat("\u00a7a[IA03] 物品栏类型: " + inventory.getClass().getSimpleName());

            // Step 2: 获取容器大小
            int size = reflectGetContainerSize(inventory);
            chat("\u00a7a[IA03] 槽位数: " + size);

            // Step 3: 遍历所有槽位
            int nonEmpty = 0;
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = reflectGetItem(inventory, slot);
                if (stack != null && !stack.isEmpty()) {
                    nonEmpty++;
                    String itemName = stack.getHoverName().getString();
                    int count = stack.getCount();
                    chat("\u00a7b  [" + slot + "] " + itemName + " x" + count);
                }
            }

            if (nonEmpty == 0) {
                chat("\u00a77  (物品栏为空)");
            } else {
                chat("\u00a7a[IA03] 共 " + nonEmpty + " 个非空槽位");
            }

        } catch (Exception e) {
            LOGGER.error("[IA03] 反射读取物品栏失败", e);
            chat("\u00a7c[IA03] 读取失败: " + e.getMessage());
        }
    }

    // ── 反射工具方法 ──

    /**
     * 反射调用 getInventory() 方法。
     * InventoryVehicleEntity 声明了 public getInventory() 返回 SparseSimpleInventory。
     */
    private Object reflectGetInventory(Entity vehicle) throws Exception {
        // 尝试 public 方法
        try {
            Method m = vehicle.getClass().getMethod("getInventory");
            return m.invoke(vehicle);
        } catch (NoSuchMethodException e) {
            // 回退: 直接读 inventory 字段
            chat("\u00a7e[IA03] getInventory() 未找到，尝试读取字段...");
            Class<?> clz = vehicle.getClass();
            while (clz != null && clz != Object.class) {
                try {
                    Field f = clz.getDeclaredField("inventory");
                    f.setAccessible(true);
                    return f.get(vehicle);
                } catch (NoSuchFieldException ignored) {}
                clz = clz.getSuperclass();
            }
            throw new NoSuchFieldException(
                    "inventory field not found in " + vehicle.getClass().getName());
        }
    }

    /**
     * 反射调用 getContainerSize()。
     * SimpleContainer 实现 Container.getContainerSize()，
     * 在 Forge 混淆映射中可能是 m_6643_()。
     */
    private int reflectGetContainerSize(Object inventory) throws Exception {
        // 尝试 SRG 名
        String[] methodNames = {"getContainerSize", "m_6643_"};
        for (String name : methodNames) {
            try {
                Method m = inventory.getClass().getMethod(name);
                return (int) m.invoke(inventory);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(
                "getContainerSize/m_6643_ not found on " + inventory.getClass().getName());
    }

    /**
     * 反射调用 getItem(int slot)。
     * SimpleContainer 实现 Container.getItem(int)，
     * 在 Forge 混淆映射中可能是 m_8020_(int)。
     */
    private ItemStack reflectGetItem(Object inventory, int slot) throws Exception {
        String[] methodNames = {"getItem", "m_8020_"};
        for (String name : methodNames) {
            try {
                Method m = inventory.getClass().getMethod(name, int.class);
                return (ItemStack) m.invoke(inventory, slot);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(
                "getItem/m_8020_ not found on " + inventory.getClass().getName());
    }

    /**
     * 检查实体是否为 InventoryVehicleEntity 或其子类。
     */
    private boolean isInventoryVehicle(Entity entity) {
        Class<?> clz = entity.getClass();
        while (clz != null && clz != Object.class) {
            if (clz.getSimpleName().equals("InventoryVehicleEntity")) {
                return true;
            }
            clz = clz.getSuperclass();
        }
        return false;
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
