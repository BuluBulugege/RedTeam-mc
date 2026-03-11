package com.redblue.red.modules.alexsmobs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AM-03: Arbitrary entity teleport via MessageSyncEntityPos (index 11).
 *
 * Wire: writeInt(entityId) + writeDouble(posX) + writeDouble(posY) + writeDouble(posZ)
 *
 * Target must implement IFalconry or be EntityStraddleboard.
 * No distance check, no ownership check, no coordinate bounds.
 * Also sets deltaMovement to the same coords (likely a bug in the mod).
 */
public class AM03_EntityTeleport implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_SYNC_ENTITY_POS = 11;

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEntity("entity_id", "目标实体"),
        ConfigParam.ofEnum("coord_source", "坐标来源",
                "CUSTOM", "CUSTOM", "VOID", "SKY_LIMIT"),
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                getParam("custom_x").ifPresent(p -> p.set((float) mc.player.getX()));
                getParam("custom_y").ifPresent(p -> p.set((float) mc.player.getY()));
                getParam("custom_z").ifPresent(p -> p.set((float) mc.player.getZ()));
            }
        }).visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("custom_x", "X", 0f, -30000000f, 30000000f)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("custom_y", "Y", 64f, -64f, 320f)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofFloat("custom_z", "Z", 0f, -30000000f, 30000000f)
                .visibleWhen("coord_source", "CUSTOM"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    @Override public String id() { return "am03_entity_teleport"; }
    @Override public String name() { return "实体传送"; }
    @Override public String description() {
        return "将猎鹰/滑板实体传送到任意坐标";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 用准星对准目标实体（猎鹰或滑板），entity_id 会自动填入。\n"
            + "2. 在「坐标来源」中选择传送目的地：\n"
            + "   - 自定义：手动填写 X/Y/Z 坐标。\n"
            + "   - 虚空：传送到虚空以下。\n"
            + "   - 天空上限：传送到天空上限以上。\n"
            + "3. 点击「执行」发送一次，或开启自动模式按间隔持续发送。\n\n"
            + "[参数说明]\n"
            + "entity_id    — 目标实体，准星选取或手动输入。\n"
            + "coord_source — 坐标来源：CUSTOM(自定义) / VOID(虚空) / SKY_LIMIT(天空上限)。\n"
            + "custom_x/y/z — 自定义坐标，仅在 coord_source=CUSTOM 时生效。\n"
            + "interval     — 自动模式下每次发送的间隔，单位为 tick。";
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
        doTeleport(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doTeleport(mc);
    }

    private void doTeleport(Minecraft mc) {
        int entityId = getParam("entity_id").map(ConfigParam::getInt).orElse(0);
        String source = getParam("coord_source").map(ConfigParam::getString).orElse("CUSTOM");

        double x, y, z;
        switch (source) {
            case "VOID" -> { x = 0; y = -512; z = 0; }
            case "SKY_LIMIT" -> { x = 0; y = 30000000; z = 0; }
            default -> {
                x = getParam("custom_x").map(ConfigParam::getFloat).orElse(0f);
                y = getParam("custom_y").map(ConfigParam::getFloat).orElse(64f);
                z = getParam("custom_z").map(ConfigParam::getFloat).orElse(0f);
            }
        }

        sendTeleport(entityId, x, y, z);
    }

    private void sendTeleport(int entityId, double x, double y, double z) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SYNC_ENTITY_POS);
            buf.writeInt(entityId);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
        });
    }
}
