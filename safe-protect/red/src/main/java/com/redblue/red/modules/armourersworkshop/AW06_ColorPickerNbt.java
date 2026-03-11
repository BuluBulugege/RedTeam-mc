package com.redblue.red.modules.armourersworkshop;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AW06: UpdateColorPickerPacket (ID 129) - ItemStack NBT injection.
 *
 * Server checks Objects.equals(holdItemStack.getItem(), this.itemStack.getItem())
 * then does player.setItemInHand(hand, this.itemStack) -- full replacement.
 * NBT/DataComponent data is entirely client-controlled.
 *
 * Wire format (after 4-byte packetId=129):
 *   writeEnum(InteractionHand) -> writeVarInt(ordinal)
 *   writeItem(ItemStack)       -> vanilla ItemStack serialization
 */
public class AW06_ColorPickerNbt implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("armourers_workshop", "aw-channel");
    private static final int PACKET_ID = 129;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("hand", "手持位置", "MAIN_HAND",
                "MAIN_HAND", "OFF_HAND"),
        ConfigParam.ofString("enchant_id", "附魔ID",
                "minecraft:sharpness"),
        ConfigParam.ofInt("enchant_level", "附魔等级",
                255, 1, 32767),
        ConfigParam.ofString("custom_name", "自定义显示名称", "")
    );

    @Override public String id() { return "aw06_color_picker_nbt"; }
    @Override public String name() { return "取色器NBT注入"; }
    @Override public String description() {
        return "通过取色器注入畸形NBT数据";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持ArmourersWorkshop的取色器(ColorPicker)物品\n"
            + "2. 选择手持位置（主手或副手）\n"
            + "3. 配置要注入的附魔和自定义名称\n"
            + "4. 执行以替换手中物品的NBT数据\n\n"
            + "[参数说明]\n"
            + "手持位置: 主手(MAIN_HAND)或副手(OFF_HAND)\n"
            + "附魔ID: 要注入的附魔标识符\n"
            + "附魔等级: 附魔等级数值\n"
            + "自定义显示名称: 物品的自定义名称（留空则不修改）\n\n"
            + "[注意事项]\n"
            + "执行前必须手持取色器物品，否则无效。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("armourers_workshop");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        String handStr = getParam("hand").map(ConfigParam::getString)
                .orElse("MAIN_HAND");
        InteractionHand hand = "OFF_HAND".equals(handStr)
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

        ItemStack held = mc.player.getItemInHand(hand);
        if (held.isEmpty()) return;

        // Clone and modify
        ItemStack modified = held.copy();
        injectNbt(modified);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeInt(PACKET_ID);
            buf.writeEnum(hand);
            buf.writeItem(modified);
        });
    }

    private void injectNbt(ItemStack stack) {
        String enchId = getParam("enchant_id").map(ConfigParam::getString)
                .orElse("minecraft:sharpness");
        int enchLvl = getParam("enchant_level").map(ConfigParam::getInt)
                .orElse(255);
        String customName = getParam("custom_name").map(ConfigParam::getString)
                .orElse("");

        // Add enchantment via NBT
        var tag = stack.getOrCreateTag();
        var enchList = new net.minecraft.nbt.ListTag();
        var enchTag = new net.minecraft.nbt.CompoundTag();
        enchTag.putString("id", enchId);
        enchTag.putShort("lvl", (short) enchLvl);
        enchList.add(enchTag);
        tag.put("Enchantments", enchList);

        // Custom display name
        if (!customName.isEmpty()) {
            var display = tag.getCompound("display");
            display.putString("Name",
                    "{\"text\":\"" + customName + "\"}");
            tag.put("display", display);
        }
    }
}
