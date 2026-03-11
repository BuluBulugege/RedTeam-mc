package com.redblue.red.modules.limitlessvehicle;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * LV-06 (CONFIRMED): Remote vehicle display change -- no ownership/distance check.
 *
 * Handler lambda$onClientMessageReceived$0 verified via javap:
 * - Gets sender.level().getEntity(msg.vehicleEntityId)
 * - Checks instanceof AbstractVehicle
 * - Directly calls vehicle.setDisplayId(msg.displayId)
 * - NO hasPassenger check, NO distance check, NO displayId validation
 *
 * Wire format (index 114):
 *   writeByte(114)
 *   writeInt(vehicleEntityId)
 *   writeResourceLocation(displayId)   // m_130085_ = writeUtf(rl.toString())
 */
public class LV05_DisplaySpoof implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("LV05");

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ywzj_vehicle", "ywzj_vehicle_channel");
    private static final int IDX_CHANGE_DISPLAY = 114;
    private static final String ABSTRACT_VEHICLE_CLASS =
            "org.ywzj.vehicle.entity.vehicle.AbstractVehicle";
    private static final String DISPLAY_TOOL_SCREEN_CLASS =
            "org.ywzj.vehicle.client.screen.VehicleDisplayToolScreen";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEntity("vehicle_id", "目标载具"),
        ConfigParam.ofAction("list_displays", "查看可用外观列表", this::listDisplays),
        ConfigParam.ofAction("open_display_gui", "打开外观选择器", this::openDisplayGui),
        ConfigParam.ofString("display_id", "外观ID", "ywzj_vehicle:ztz99a_desert"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lv05_display_spoof"; }
    @Override public String name() { return "外观篡改"; }
    @Override public String description() {
        return "远程修改任意载具外观，无乘客/距离校验";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 用准星选取按钮对准目标载具\n"
            + "2. 输入要设置的外观ID(ResourceLocation格式)\n"
            + "   有效ID: 该载具类型的合法外观\n"
            + "   无效ID: 可能导致客户端渲染异常\n"
            + "3. 点击执行\n\n"
            + "[漏洞原理]\n"
            + "LV-06: handler无任何校验，直接setDisplayId";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("ywzj_vehicle");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        doSpoof(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doSpoof(mc);
    }

    private void doSpoof(Minecraft mc) {
        int vehicleId = getParam("vehicle_id").map(ConfigParam::getInt).orElse(0);
        if (vehicleId == 0) return;
        String displayIdStr = getParam("display_id").map(ConfigParam::getString)
                .orElse("ywzj_vehicle:ztz99a_desert");
        ResourceLocation displayId = new ResourceLocation(displayIdStr);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_CHANGE_DISPLAY);
            buf.writeInt(vehicleId);
            buf.writeResourceLocation(displayId); // m_130085_
        });
    }

    /**
     * 反射打开 VehicleDisplayToolScreen，列出目标载具的所有可用外观。
     * 构造器: VehicleDisplayToolScreen(AbstractVehicle)
     * 内部通过 ClientAssetsManager.getVehicleDisplay → getVariableDisplay 获取外观列表。
     */
    @SuppressWarnings("unchecked")
    private void listDisplays() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Entity target = resolveTarget(mc);
        if (target == null) return;

        try {
            // 反射获取 variableDisplayIds
            Class<?> screenClz = Class.forName(DISPLAY_TOOL_SCREEN_CLASS);
            Class<?> vehicleClz = Class.forName(ABSTRACT_VEHICLE_CLASS);
            Constructor<?> ctor = screenClz.getDeclaredConstructor(vehicleClz);
            ctor.setAccessible(true);
            Object screen = ctor.newInstance(target);

            // 读取 variableDisplayIds 字段
            Field idsField = screenClz.getDeclaredField("variableDisplayIds");
            idsField.setAccessible(true);
            List<ResourceLocation> ids = (List<ResourceLocation>) idsField.get(screen);

            if (ids == null || ids.isEmpty()) {
                chat("\u00a7c[LV05] 该载具没有可变外观");
                return;
            }

            chat("\u00a7a[LV05] 可用外观 (" + ids.size() + " 个):");
            for (int i = 0; i < ids.size(); i++) {
                chat("\u00a7b  [" + i + "] " + ids.get(i).toString());
            }
        } catch (Exception e) {
            LOGGER.error("[LV05] 查询外观列表失败", e);
            chat("\u00a7c[LV05] 查询失败: " + e.getMessage());
        }
    }

    /**
     * 直接反射打开 VehicleDisplayToolScreen GUI。
     */
    private void openDisplayGui() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Entity target = resolveTarget(mc);
        if (target == null) return;

        try {
            Class<?> screenClz = Class.forName(DISPLAY_TOOL_SCREEN_CLASS);
            Class<?> vehicleClz = Class.forName(ABSTRACT_VEHICLE_CLASS);
            Constructor<?> ctor = screenClz.getDeclaredConstructor(vehicleClz);
            ctor.setAccessible(true);
            Screen screen = (Screen) ctor.newInstance(target);
            mc.setScreen(screen);
        } catch (Exception e) {
            LOGGER.error("[LV05] 打开外观选择器失败", e);
            chat("\u00a7c[LV05] 打开失败: " + e.getMessage());
        }
    }

    private Entity resolveTarget(Minecraft mc) {
        int vehicleId = getParam("vehicle_id").map(ConfigParam::getInt).orElse(0);
        if (vehicleId > 0) {
            Entity e = mc.level.getEntity(vehicleId);
            if (e == null) {
                chat("\u00a7c[LV05] 实体 #" + vehicleId + " 不存在");
                return null;
            }
            return e;
        }
        // fallback: 准星或骑乘
        Entity target = mc.crosshairPickEntity;
        if (target == null) target = mc.player.getVehicle();
        if (target == null) {
            chat("\u00a7c[LV05] 请先选取目标载具或输入实体ID");
            return null;
        }
        return target;
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
