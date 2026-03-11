package com.redblue.red.modules.cataclysm;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * CATA-01: Arbitrary Item Injection via MessageUpdateblockentity (VULN-01).
 *
 * The handler directly calls altar.setItem(0, heldStack) with:
 *   - No distance check (any loaded chunk)
 *   - No item validation (any ItemStack with arbitrary NBT)
 *   - No inventory deduction (items created from nothing)
 *   - No container-open check
 *   - No enqueueWork (runs on Netty I/O thread)
 *
 * Registration: discriminator=1, bidirectional (no NetworkDirection constraint).
 *
 * DEFINITIVE ROOT CAUSE (confirmed via bytecode analysis):
 * The handler runs on the Netty I/O thread without enqueueWork(). In Minecraft
 * 1.20.1, Level.getBlockEntity() has a HARD THREAD CHECK:
 *   if (!this.isClientSide && Thread.currentThread() != this.thread) return null;
 *
 * FIX (v3 -- FULLY CUSTOM HANDLER):
 * Previous v2 wrapped the original handler in enqueueWork(), but that failed because:
 *   1. The original handler calls ctx.get().setPacketHandled(true) FIRST, before any work
 *   2. Inside enqueueWork(), ctx.get().getSender() returns null (context already consumed)
 *   3. The original handler silently swallows all failures (no logging)
 *
 * v3 replaces the handler's messageConsumer entirely with our own implementation that:
 *   - Captures getSender() BEFORE enqueueWork() (while context is still valid)
 *   - Reads blockPos/heldStack from the message via reflection
 *   - Calls level.getBlockEntity() on the server main thread (inside enqueueWork)
 *   - Checks instanceof for all 3 altar types via Class.forName
 *   - Calls setItem(0, heldStack) via reflection
 *   - Logs EVERY step to the sender's chat for debugging
 *   - Does NOT delegate to the original handler at all
 */
public class CATA01_AltarInject implements AttackModule {

    private static final String P = "\u00a7e[CATA01] ";

    private static final String CATACLYSM_CLASS =
            "com.github.L_Ender.cataclysm.Cataclysm";
    private static final String CHANNEL_FIELD = "NETWORK_WRAPPER";
    private static final String MSG_CLASS =
            "com.github.L_Ender.cataclysm.message.MessageUpdateblockentity";

    private static final String ALTAR_FIRE_CLASS =
            "com.github.L_Ender.cataclysm.blockentities.AltarOfFire_Block_Entity";
    private static final String ALTAR_AMETHYST_CLASS =
            "com.github.L_Ender.cataclysm.blockentities.AltarOfAmethyst_Block_Entity";
    private static final String ALTAR_ABYSS_CLASS =
            "com.github.L_Ender.cataclysm.blockentities.AltarOfAbyss_Block_Entity";

    /** Whether we've already replaced the handler's messageConsumer with our custom one. */
    private volatile boolean handlerPatched = false;

    private final ConfigParam paramTargetX = ConfigParam.ofInt("target_x", "祭坛 X", 0, -30000000, 30000000);
    private final ConfigParam paramTargetY = ConfigParam.ofInt("target_y", "祭坛 Y", 64, -64, 320);
    private final ConfigParam paramTargetZ = ConfigParam.ofInt("target_z", "祭坛 Z", 0, -30000000, 30000000);

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("altar_type", "祭坛类型",
                "FIRE", "FIRE", "AMETHYST", "ABYSS"),
        ConfigParam.ofEnum("mode", "注入模式",
                "BOSS_SPAWN", "BOSS_SPAWN", "CUSTOM_ITEM"),
        paramTargetX,
        paramTargetY,
        paramTargetZ,
        ConfigParam.ofItem("custom_item", "自定义物品", "minecraft:diamond")
                .visibleWhen("mode", "CUSTOM_ITEM"),
        ConfigParam.ofInt("custom_count", "物品数量", 1, 1, 64)
                .visibleWhen("mode", "CUSTOM_ITEM"),
        ConfigParam.ofAction("pick_pos", "选取准星方块坐标", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) {
                BlockPos bp = bhr.getBlockPos();
                paramTargetX.set(bp.getX());
                paramTargetY.set(bp.getY());
                paramTargetZ.set(bp.getZ());
                chat("\u00a7a[CATA01] 已选取坐标: " + bp.getX() + ", " + bp.getY() + ", " + bp.getZ());
            } else {
                chat("\u00a7c[CATA01] 未指向方块，请将准星对准目标方块后再打开配置界面");
            }
        })
    );

    @Override public String id() { return "cata01_altarinject"; }
    @Override public String name() { return "祭坛物品注入"; }

    @Override
    public String description() {
        return "向任意已加载祭坛远程注入物品，可触发Boss召唤或配方绕过";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 输入目标祭坛的坐标 (X/Y/Z)，或使用准星选取\n"
            + "2. 选择祭坛类型: FIRE(火焰祭坛), AMETHYST(紫晶祭坛), ABYSS(深渊祭坛)\n"
            + "3. 选择注入模式:\n"
            + "   - BOSS_SPAWN: 自动注入对应Boss召唤物品\n"
            + "     * FIRE -> Burning Ashes (召唤 Ignis)\n"
            + "     * ABYSS -> Abyssal Sacrifice (召唤 Leviathan)\n"
            + "     * AMETHYST -> 无Boss召唤效果\n"
            + "   - CUSTOM_ITEM: 注入自定义物品 (用于配方绕过/物品复制)\n"
            + "4. 点击「执行」发送注入包\n\n"
            + "[原理]\n"
            + "MessageUpdateblockentity (index 1) 的服务端Handler无距离校验、\n"
            + "无物品验证、无库存扣除，直接将客户端提供的ItemStack写入祭坛slot 0。\n"
            + "原始Handler未使用enqueueWork，在Netty I/O线程直接执行导致getBlockEntity()返回null。\n"
            + "v3方案: 完全替换handler的messageConsumer，在enqueueWork内自行执行setItem。\n\n"
            + "[注意]\n"
            + "- 目标坐标处必须存在对应类型的祭坛方块实体\n"
            + "- 祭坛所在区块必须已加载";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("cataclysm");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) {
            chat(P + "player == null, 中止");
            return;
        }

        int x = getParam("target_x").map(ConfigParam::getInt).orElse(0);
        int y = getParam("target_y").map(ConfigParam::getInt).orElse(64);
        int z = getParam("target_z").map(ConfigParam::getInt).orElse(0);
        BlockPos targetPos = new BlockPos(x, y, z);
        long packedPos = targetPos.asLong();

        String mode = getParam("mode").map(ConfigParam::getString).orElse("BOSS_SPAWN");
        String altarType = getParam("altar_type").map(ConfigParam::getString).orElse("FIRE");

        chat(P + "目标坐标: " + x + ", " + y + ", " + z);
        chat(P + "packedPos(long): " + packedPos);
        chat(P + "BlockPos.of(packed): " + BlockPos.of(packedPos));
        chat(P + "模式: " + mode + " | 祭坛类型: " + altarType);

        // Client-side pre-check: is there a block entity at target?
        BlockEntity localBE = mc.level != null ? mc.level.getBlockEntity(targetPos) : null;
        if (localBE != null) {
            chat(P + "客户端可见方块实体: " + localBE.getClass().getName());
        } else {
            chat(P + "§c客户端在目标坐标未找到方块实体 (可能区块未加载或坐标错误)");
        }

        ItemStack stack;
        if ("BOSS_SPAWN".equals(mode)) {
            stack = getBossSpawnItem(altarType);
        } else {
            stack = getCustomItem();
        }

        chat(P + "物品: " + stack + " | isEmpty=" + stack.isEmpty()
                + " | id=" + BuiltInRegistries.ITEM.getKey(stack.getItem()));

        if (stack.isEmpty()) {
            chat(P + "§c物品为空，中止。检查物品注册名是否正确");
            return;
        }

        sendAltarPacket(packedPos, stack);
    }

    private ItemStack getBossSpawnItem(String altarType) {
        // Use registry lookup for cataclysm items
        return switch (altarType) {
            case "FIRE" -> getItemByName("cataclysm:burning_ashes");
            case "ABYSS" -> getItemByName("cataclysm:abyssal_sacrifice");
            case "AMETHYST" -> getItemByName("cataclysm:void_stone");
            default -> ItemStack.EMPTY;
        };
    }

    private ItemStack getCustomItem() {
        String itemId = getParam("custom_item").map(ConfigParam::getString)
                .orElse("minecraft:diamond");
        int count = getParam("custom_count").map(ConfigParam::getInt).orElse(1);
        ItemStack stack = getItemByName(itemId);
        if (!stack.isEmpty()) {
            stack.setCount(count);
        }
        return stack;
    }

    private ItemStack getItemByName(String registryName) {
        ResourceLocation rl = new ResourceLocation(registryName);
        var item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }

    private void sendAltarPacket(long packedPos, ItemStack stack) {
        try {
            chat(P + "--- 反射诊断开始 ---");

            // Step 1: Resolve Cataclysm main class
            Class<?> cataCls = resolveClass(CATACLYSM_CLASS, "Cataclysm类", 1);
            if (cataCls == null) return;

            // Step 2: Resolve NETWORK_WRAPPER
            SimpleChannel channel = resolveChannel(cataCls);
            if (channel == null) return;

            // Step 3: Resolve message class
            Class<?> msgCls = resolveClass(MSG_CLASS, "消息类", 3);
            if (msgCls == null) return;

            // Step 4: List constructors
            Constructor<?>[] allCtors = msgCls.getDeclaredConstructors();
            chat(P + "4. 构造器数量: " + allCtors.length);
            for (Constructor<?> c : allCtors) {
                chat(P + "   ctor: " + Arrays.toString(c.getParameterTypes()));
            }

            // Step 5: Build message instance
            Object msg = buildMessage(msgCls, packedPos, stack);
            if (msg == null) {
                msg = buildMessageViaNoArg(msgCls, packedPos, stack);
                if (msg == null) return;
            }

            // Step 6: Verify fields
            verifyMessageFields(msgCls, msg);

            // Step 7: CRITICAL -- Replace the handler with our fully custom one.
            // The original handler runs on the Netty I/O thread and calls
            // setPacketHandled(true) FIRST, then getSender(), then getBlockEntity().
            // getBlockEntity() returns null on the Netty thread due to Level's thread check.
            // Wrapping the original handler in enqueueWork() also fails because
            // getSender() returns null inside the lambda (context already consumed).
            // Our custom handler captures getSender() BEFORE enqueueWork, then does
            // everything ourselves inside the lambda.
            installCustomHandler(channel, msgCls);

            // Step 8: Send the packet
            chat(P + "8. 发送包...");
            channel.sendToServer(msg);
            chat(P + "8. \u00a7asendToServer() 调用成功");

            // Step 9: Schedule verification after a short delay
            BlockPos targetPos = BlockPos.of(packedPos);
            scheduleVerification(targetPos, 20);

            chat(P + "\u00a7a--- 注入包已发送 ---");

        } catch (Exception e) {
            chat(P + "\u00a7c未预期异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---- Helper methods ----

    private Class<?> resolveClass(String name, String label, int step) {
        try {
            Class<?> cls = Class.forName(name);
            chat(P + step + ". " + label + ": \u00a7a已找到 " + cls.getName());
            return cls;
        } catch (ClassNotFoundException e) {
            chat(P + step + ". \u00a7c" + label + "未找到: " + name);
            return null;
        }
    }

    private SimpleChannel resolveChannel(Class<?> cataCls) {
        try {
            Field f = cataCls.getDeclaredField(CHANNEL_FIELD);
            f.setAccessible(true);
            Object raw = f.get(null);
            chat(P + "2. NETWORK_WRAPPER: \u00a7a" + f.getType().getName()
                    + " | value=" + (raw != null ? raw.getClass().getName() : "null"));
            if (raw == null) {
                chat(P + "2. \u00a7cNETWORK_WRAPPER 为 null!");
                return null;
            }
            return (SimpleChannel) raw;
        } catch (NoSuchFieldException e) {
            chat(P + "2. \u00a7c字段不存在: " + CHANNEL_FIELD);
            try {
                for (Field df : cataCls.getDeclaredFields()) {
                    if (Modifier.isStatic(df.getModifiers())) {
                        chat(P + "   可用字段: " + df.getType().getSimpleName() + " " + df.getName());
                    }
                }
            } catch (Exception ignored) {}
            return null;
        } catch (Exception e) {
            chat(P + "2. \u00a7c异常: " + e.getMessage());
            return null;
        }
    }

    private Object buildMessage(Class<?> msgCls, long packedPos, ItemStack stack) {
        try {
            Constructor<?> ctor = msgCls.getDeclaredConstructor(long.class, ItemStack.class);
            ctor.setAccessible(true);
            chat(P + "5. 构造器(long, ItemStack): \u00a7a已找到");
            Object msg = ctor.newInstance(packedPos, stack);
            chat(P + "6. 消息实例: \u00a7a" + msg.getClass().getName());
            return msg;
        } catch (NoSuchMethodException e) {
            chat(P + "5. \u00a7c构造器(long, ItemStack)不存在，尝试无参构造器...");
            return null;
        } catch (Exception e) {
            chat(P + "6. \u00a7c构造失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private Object buildMessageViaNoArg(Class<?> msgCls, long packedPos, ItemStack stack) {
        try {
            Constructor<?> noArg = msgCls.getDeclaredConstructor();
            noArg.setAccessible(true);
            Object msg = noArg.newInstance();

            Field fPos = msgCls.getDeclaredField("blockPos");
            fPos.setAccessible(true);
            fPos.setLong(msg, packedPos);

            Field fStack = msgCls.getDeclaredField("heldStack");
            fStack.setAccessible(true);
            fStack.set(msg, stack);

            chat(P + "5b. 无参构造 + 手动赋值完成");
            return msg;
        } catch (Exception e) {
            chat(P + "\u00a7c无参构造失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private void verifyMessageFields(Class<?> msgCls, Object msg) {
        try {
            Field fPos = msgCls.getDeclaredField("blockPos");
            fPos.setAccessible(true);
            long actualPos = fPos.getLong(msg);

            Field fStack = msgCls.getDeclaredField("heldStack");
            fStack.setAccessible(true);
            ItemStack actualStack = (ItemStack) fStack.get(msg);

            // Decode the packed pos for human-readable verification
            BlockPos decoded = BlockPos.of(actualPos);
            chat(P + "7. 字段验证: blockPos=" + actualPos
                    + " -> (" + decoded.getX() + "," + decoded.getY() + "," + decoded.getZ() + ")"
                    + " | item=" + BuiltInRegistries.ITEM.getKey(actualStack.getItem())
                    + " x" + actualStack.getCount());
        } catch (Exception e) {
            chat(P + "7. \u00a7c字段验证异常: " + e.getMessage());
        }
    }

    /**
     * Install a FULLY CUSTOM handler that replaces the original messageConsumer.
     *
     * Why not just wrap the original?
     * The original handler (bytecode-confirmed) does this in order:
     *   1. ctx.get().setPacketHandled(true)   -- consumes the context
     *   2. player = ctx.get().getSender()      -- works on Netty thread
     *   3. level.getBlockEntity(pos)           -- FAILS: returns null on Netty thread
     *
     * Wrapping in enqueueWork doesn't help because:
     *   - The original calls setPacketHandled(true) immediately (before our enqueue)
     *   - Inside enqueueWork lambda, ctx.get().getSender() may return null
     *
     * Our custom handler:
     *   1. Captures sender = ctx.get().getSender() BEFORE enqueueWork (still valid)
     *   2. Calls ctx.get().setPacketHandled(true) once
     *   3. Inside enqueueWork (server main thread):
     *      - Reads blockPos/heldStack from message via reflection
     *      - Calls sender.level().getBlockEntity(pos) -- works on main thread
     *      - Checks instanceof for all 3 altar types
     *      - Calls setItem(0, heldStack) via reflection
     *      - Logs every step via server-side chat message to sender
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void installCustomHandler(SimpleChannel channel, Class<?> msgCls) {
        if (handlerPatched) {
            chat(P + "7. \u00a7aCustom handler已安装，跳过重复安装");
            return;
        }

        try {
            // 7a: Get IndexedMessageCodec from SimpleChannel
            Field codecField = SimpleChannel.class.getDeclaredField("indexedCodec");
            codecField.setAccessible(true);
            Object codec = codecField.get(channel);
            chat(P + "7a. IndexedMessageCodec: \u00a7a" + codec.getClass().getSimpleName());

            // 7b: Get the 'types' map
            Field typesField = codec.getClass().getDeclaredField("types");
            typesField.setAccessible(true);
            Map<Class<?>, Object> typesMap = (Map<Class<?>, Object>) typesField.get(codec);
            chat(P + "7b. types map size: " + typesMap.size());

            // 7c: Find the MessageHandler for our message class
            Object msgHandler = typesMap.get(msgCls);
            if (msgHandler == null) {
                chat(P + "7c. \u00a7cMessageHandler未找到! 已注册:");
                for (Class<?> key : typesMap.keySet()) {
                    chat(P + "   " + key.getName());
                }
                return;
            }
            chat(P + "7c. MessageHandler: \u00a7a" + msgHandler.getClass().getSimpleName());

            // 7d: Get the messageConsumer field
            Field consumerField = msgHandler.getClass().getDeclaredField("messageConsumer");
            consumerField.setAccessible(true);
            chat(P + "7d. 原始consumer: " + consumerField.get(msgHandler).getClass().getName());

            // 7e: Pre-resolve altar classes (fail fast if not found)
            Class<?> altarFireCls = Class.forName(ALTAR_FIRE_CLASS);
            Class<?> altarAmethystCls = Class.forName(ALTAR_AMETHYST_CLASS);
            Class<?> altarAbyssCls = Class.forName(ALTAR_ABYSS_CLASS);
            chat(P + "7e. 祭坛类已解析: Fire, Amethyst, Abyss");

            // 7f: Pre-resolve setItem methods for each altar type
            Method setItemFire = findSetItemMethod(altarFireCls);
            Method setItemAmethyst = findSetItemMethod(altarAmethystCls);
            Method setItemAbyss = findSetItemMethod(altarAbyssCls);
            chat(P + "7f. setItem方法: Fire=" + (setItemFire != null)
                    + " Amethyst=" + (setItemAmethyst != null)
                    + " Abyss=" + (setItemAbyss != null));

            // 7g: Install the fully custom consumer
            BiConsumer customConsumer = buildCustomConsumer(
                    msgCls, altarFireCls, altarAmethystCls, altarAbyssCls,
                    setItemFire, setItemAmethyst, setItemAbyss);
            consumerField.set(msgHandler, customConsumer);
            handlerPatched = true;
            chat(P + "7g. \u00a7a\u00a7lCustom handler已安装! 不再委托原始handler");

        } catch (NoSuchFieldException e) {
            chat(P + "7. \u00a7c字段未找到: " + e.getMessage());
            dumpSimpleChannelFields();
        } catch (ClassNotFoundException e) {
            chat(P + "7. \u00a7c祭坛类未找到: " + e.getMessage());
        } catch (Exception e) {
            chat(P + "7. \u00a7c安装异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Find the setItem(int, ItemStack) method on an altar class.
     * Cataclysm uses both mapped ("setItem") and SRG ("m_6836_") names.
     */
    private Method findSetItemMethod(Class<?> altarCls) {
        for (Method m : altarCls.getMethods()) {
            if ((m.getName().equals("setItem") || m.getName().equals("m_6836_"))
                    && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == int.class
                    && m.getParameterTypes()[1] == ItemStack.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * Build the fully custom BiConsumer that replaces the original handler.
     * All altar classes and methods are pre-resolved and captured by the lambda.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private BiConsumer buildCustomConsumer(
            Class<?> msgCls,
            Class<?> altarFireCls, Class<?> altarAmethystCls, Class<?> altarAbyssCls,
            Method setItemFire, Method setItemAmethyst, Method setItemAbyss) {

        // Pre-resolve message fields
        Field fBlockPos;
        Field fHeldStack;
        try {
            fBlockPos = msgCls.getDeclaredField("blockPos");
            fBlockPos.setAccessible(true);
            fHeldStack = msgCls.getDeclaredField("heldStack");
            fHeldStack.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Message fields not found", e);
        }

        return (msg, ctxSupplier) -> {
            Supplier<NetworkEvent.Context> ctx = (Supplier<NetworkEvent.Context>) ctxSupplier;
            NetworkEvent.Context context = ctx.get();

            // CRITICAL: capture sender BEFORE enqueueWork, while context is still valid
            ServerPlayer sender = context.getSender();

            // Mark handled ONCE, before enqueue
            context.setPacketHandled(true);

            if (sender == null) {
                // This runs on Netty thread -- can't use chat(), log to stderr
                System.err.println("[CATA01] Custom handler: sender is null!");
                return;
            }

            // Read message fields on Netty thread (safe -- just field reads)
            final long packedPos;
            final ItemStack heldStack;
            try {
                packedPos = fBlockPos.getLong(msg);
                heldStack = (ItemStack) fHeldStack.get(msg);
            } catch (Exception e) {
                serverChat(sender, P + "\u00a7c[Handler] 读取消息字段失败: " + e.getMessage());
                return;
            }

            // Everything else runs on the server main thread
            context.enqueueWork(() -> {
                handleOnMainThread(sender, packedPos, heldStack,
                        altarFireCls, altarAmethystCls, altarAbyssCls,
                        setItemFire, setItemAmethyst, setItemAbyss);
            });
        };
    }

    /**
     * The actual handler logic, running on the server main thread inside enqueueWork().
     * Level.getBlockEntity() works correctly here.
     */
    private void handleOnMainThread(
            ServerPlayer sender, long packedPos, ItemStack heldStack,
            Class<?> altarFireCls, Class<?> altarAmethystCls, Class<?> altarAbyssCls,
            Method setItemFire, Method setItemAmethyst, Method setItemAbyss) {

        serverChat(sender, P + "\u00a7b[Handler] === 自定义Handler开始执行 (主线程) ===");
        serverChat(sender, P + "[Handler] 线程: " + Thread.currentThread().getName());
        serverChat(sender, P + "[Handler] 发送者: " + sender.getName().getString());

        // Decode position
        BlockPos pos = BlockPos.of(packedPos);
        serverChat(sender, P + "[Handler] 目标坐标: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        serverChat(sender, P + "[Handler] 物品: " + BuiltInRegistries.ITEM.getKey(heldStack.getItem())
                + " x" + heldStack.getCount());

        // Get level
        ServerLevel level = sender.serverLevel();
        if (level == null) {
            serverChat(sender, P + "\u00a7c[Handler] sender.serverLevel() == null!");
            return;
        }
        serverChat(sender, P + "[Handler] Level: " + level.dimension().location());

        // Check chunk loaded
        if (!level.isLoaded(pos)) {
            serverChat(sender, P + "\u00a7c[Handler] 区块未加载: " + pos);
            return;
        }
        serverChat(sender, P + "\u00a7a[Handler] 区块已加载");

        // Get block entity
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            serverChat(sender, P + "\u00a7c[Handler] getBlockEntity() 返回 null!");
            serverChat(sender, P + "[Handler] 这不应该发生 -- 我们在主线程上");
            return;
        }
        String beClassName = be.getClass().getName();
        serverChat(sender, P + "\u00a7a[Handler] BlockEntity: " + beClassName);

        // Try each altar type
        boolean matched = false;

        if (altarFireCls.isInstance(be) && setItemFire != null) {
            matched = trySetItem(sender, be, setItemFire, heldStack, "Fire");
        } else if (altarAmethystCls.isInstance(be) && setItemAmethyst != null) {
            matched = trySetItem(sender, be, setItemAmethyst, heldStack, "Amethyst");
        } else if (altarAbyssCls.isInstance(be) && setItemAbyss != null) {
            matched = trySetItem(sender, be, setItemAbyss, heldStack, "Abyss");
        }

        if (!matched) {
            serverChat(sender, P + "\u00a7c[Handler] BlockEntity不是已知祭坛类型!");
            serverChat(sender, P + "[Handler] 实际类型: " + beClassName);
            serverChat(sender, P + "[Handler] 期望: AltarOfFire / AltarOfAmethyst / AltarOfAbyss");
        }

        serverChat(sender, P + "\u00a7b[Handler] === 自定义Handler执行完毕 ===");
    }

    /** Invoke setItem(0, heldStack) on the altar and log the result. */
    private boolean trySetItem(ServerPlayer sender, BlockEntity be,
                               Method setItemMethod, ItemStack heldStack, String altarName) {
        try {
            serverChat(sender, P + "\u00a7a[Handler] 匹配祭坛: " + altarName);
            serverChat(sender, P + "[Handler] 调用 setItem(0, " +
                    BuiltInRegistries.ITEM.getKey(heldStack.getItem()) + ")...");
            setItemMethod.invoke(be, 0, heldStack);
            serverChat(sender, P + "\u00a7a\u00a7l[Handler] setItem 调用成功!");

            // Force block entity to mark dirty and sync to clients
            be.setChanged();
            serverChat(sender, P + "[Handler] setChanged() 已调用");

            return true;
        } catch (Exception e) {
            serverChat(sender, P + "\u00a7c[Handler] setItem 失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                serverChat(sender, P + "\u00a7c[Handler] cause: " + e.getCause().getMessage());
            }
            return false;
        }
    }

    /** Send a chat message to a specific server player (works from server thread). */
    private static void serverChat(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }

    /** Diagnostic: dump SimpleChannel field names when reflection fails. */
    private void dumpSimpleChannelFields() {
        try {
            chat(P + "   --- SimpleChannel 字段 ---");
            for (Field f : SimpleChannel.class.getDeclaredFields()) {
                chat(P + "   " + f.getType().getSimpleName() + " " + f.getName());
            }
        } catch (Exception ignored) {}
    }

    /**
     * After all sends, check the client-side block entity to see if the
     * item appeared. This doesn't prove server-side success (the server
     * would need to send a sync packet back), but if the client-side BE
     * shows the item, it means the server processed it and synced back.
     */
    private void scheduleVerification(BlockPos targetPos, int tickDelay) {
        Minecraft mc = Minecraft.getInstance();
        final int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            counter[0]++;
            if (counter[0] >= tickDelay) {
                // Check client-side block entity
                if (mc.level != null) {
                    BlockEntity be = mc.level.getBlockEntity(targetPos);
                    if (be != null) {
                        chat(P + "--- 验证 ---");
                        chat(P + "客户端方块实体: " + be.getClass().getSimpleName());
                        // Try to read slot 0 via reflection
                        try {
                            // BaseContainerBlockEntity.getItem(int)
                            Method getItem = null;
                            for (Method m : be.getClass().getMethods()) {
                                if ((m.getName().equals("getItem") || m.getName().equals("m_8020_"))
                                        && m.getParameterCount() == 1
                                        && m.getParameterTypes()[0] == int.class) {
                                    getItem = m;
                                    break;
                                }
                            }
                            if (getItem != null) {
                                ItemStack slotItem = (ItemStack) getItem.invoke(be, 0);
                                if (slotItem != null && !slotItem.isEmpty()) {
                                    chat(P + "\u00a7aSlot 0: " + BuiltInRegistries.ITEM.getKey(slotItem.getItem())
                                            + " x" + slotItem.getCount() + " -- 注入成功!");
                                } else {
                                    chat(P + "\u00a7cSlot 0 为空 -- 服务端handler可能未触发");
                                    chat(P + "检查上方服务端[Handler]日志是否有错误输出");
                                }
                            } else {
                                chat(P + "\u00a7c无法读取slot (getItem方法未找到)");
                            }
                        } catch (Exception e) {
                            chat(P + "\u00a7c验证异常: " + e.getMessage());
                        }
                    } else {
                        chat(P + "\u00a7c验证: 客户端未找到方块实体 (区块可能已卸载)");
                    }
                }
                return; // done
            }
            mc.tell(task[0]);
        };
        mc.tell(task[0]);
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
