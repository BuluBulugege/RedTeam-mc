package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-04: Dimension Teleport -- teleport to any registered dimension.
 *
 * Vuln #6 (index 59): SPacketDimensionTeleport
 * Wire: writeResourceLocation(dimensionId)
 *
 * BYTECODE CORRECTION: Report says "wand (default toolAllowed)" but actual
 * bytecode shows toolAllowed checks for CustomItems.teleporter, NOT wand.
 * Requires: teleporter tool, NO permission check (getPermission=null).
 *
 * writeResourceLocation = writeUtf(namespace) + writeUtf(path) internally
 * via FriendlyByteBuf.m_130085_
 */
public class CNPC04_DimensionTp implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_DIMENSION_TP = 59;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("dimension", "目标维度", "minecraft:the_nether",
                "minecraft:overworld",
                "minecraft:the_nether",
                "minecraft:the_end"),
        ConfigParam.ofString("custom_dimension", "自定义维度ID", ""),
        ConfigParam.ofBool("use_custom", "使用自定义维度ID", false)
    );

    @Override public String id() { return "cnpc04_dimension_tp"; }
    @Override public String name() { return "维度传送"; }
    @Override public String description() { return "无限制传送到任意维度(绕过传送门)"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持 CustomNPCs teleporter 物品\n"
            + "2. 选择目标维度或输入自定义维度ID\n"
            + "3. 点击执行即可传送\n\n"
            + "[注意]\n"
            + "报告称需要wand,但字节码验证实际需要teleporter物品。\n"
            + "无权限检查(getPermission返回null)。\n"
            + "可绕过所有原版维度旅行限制。";
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

        boolean useCustom = getParam("use_custom").map(ConfigParam::getBool).orElse(false);
        String dimStr;
        if (useCustom) {
            dimStr = getParam("custom_dimension").map(ConfigParam::getString).orElse("");
            if (dimStr.isEmpty()) return;
        } else {
            dimStr = getParam("dimension").map(ConfigParam::getString)
                    .orElse("minecraft:the_nether");
        }

        ResourceLocation dimId = new ResourceLocation(dimStr);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_DIMENSION_TP);
            buf.writeResourceLocation(dimId);
        });
    }
}
