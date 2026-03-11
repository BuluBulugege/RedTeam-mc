package com.redblue.red.util;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redblue.red.client.gui.VehicleInventoryScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Netty pipeline sniffer that intercepts S2C custom payload packets
 * and displays parsed results in chat. Injected before packet_handler
 * so the original mod handler still processes the packet normally.
 */
public class ResponseSniffer extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("NetHandler");
    private static final String HANDLER_NAME = "forge_payload_validator";
    private static final Map<ResourceLocation, ResponseParser> PARSERS = new ConcurrentHashMap<>();

    /** Inventory collection buffer: vehicleId → (slotIndex → ItemStack) */
    private static final Map<Integer, TreeMap<Integer, ItemStack>> pendingInventories = new ConcurrentHashMap<>();
    /** Tick when first slot was received for each vehicle */
    private static final Map<Integer, Long> pendingTimestamps = new ConcurrentHashMap<>();
    /** Whether to open GUI for next inventory response (set by IA03) */
    private static volatile boolean openGuiOnNext = false;
    private static final int GUI_DELAY_TICKS = 5;

    // ── Corpse death history interception ──────────────────────────────
    /** Captured death records from the most recent MessageOpenHistory response */
    private static final CopyOnWriteArrayList<DeathRecord> capturedDeaths = new CopyOnWriteArrayList<>();
    /** Listeners notified when new death records arrive */
    private static final CopyOnWriteArrayList<Consumer<List<DeathRecord>>> deathListeners = new CopyOnWriteArrayList<>();

    /** Lightweight death record extracted from Corpse mod's Death NBT */
    public record DeathRecord(UUID id, UUID playerUUID, String playerName, long timestamp,
                              double posX, double posY, double posZ, String dimension, int experience) {
        public String shortLabel() {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
            String time = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            return time + " (" + (int) posX + "," + (int) posY + "," + (int) posZ + ")";
        }
    }

    static {
        PARSERS.put(new ResourceLocation("ic_air", "main"), ResponseSniffer::parseImmersiveAircraft);
        PARSERS.put(new ResourceLocation("journeymap", "admin_save"), ResponseSniffer::parseJourneyMap);
        PARSERS.put(new ResourceLocation("corpse", "default"), ResponseSniffer::parseCorpse);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ClientboundCustomPayloadPacket pkt) {
            try {
                sniff(pkt);
            } catch (Exception e) {
                LOGGER.error("Sniffer parse error on {}", pkt.getIdentifier(), e);
            }
        }
        super.channelRead(ctx, msg);
    }

    private void sniff(ClientboundCustomPayloadPacket pkt) {
        ResourceLocation channel = pkt.getIdentifier();
        ResponseParser parser = PARSERS.get(channel);
        if (parser == null) return;

        FriendlyByteBuf original = pkt.getData();
        if (original.refCnt() <= 0) return;
        FriendlyByteBuf copy = new FriendlyByteBuf(original.copy());
        try {
            parser.parse(channel, copy);
        } finally {
            copy.release();
        }
    }

    // ── Immersive Aircraft InventoryUpdateMessage (disc=3) ──────────────
    // Wire: readInt(vehicleId) + readInt(slotIndex) + readItem(stack)
    // Server sends ONE message per slot; we collect them and open GUI.
    private static void parseImmersiveAircraft(ResourceLocation channel, FriendlyByteBuf buf) {
        int disc = buf.readByte();
        if (disc != 3) return;

        int vehicleId = buf.readInt();
        int slotIndex = buf.readInt();
        ItemStack stack;
        try {
            stack = buf.readItem();
        } catch (Exception e) {
            return;
        }

        // Accumulate into collection buffer
        pendingInventories
                .computeIfAbsent(vehicleId, k -> new TreeMap<>())
                .put(slotIndex, stack);
        pendingTimestamps.putIfAbsent(vehicleId, System.currentTimeMillis());
    }

    // ── JourneyMap admin config response (disc=0) ───────────────────────
    private static void parseJourneyMap(ResourceLocation channel, FriendlyByteBuf buf) {
        int disc = buf.readByte();
        if (disc != 0) return;

        byte magic = buf.readByte();
        if (magic != 0x2A) return;
        int type = buf.readInt();
        String dim = buf.readUtf();
        String json = buf.readUtf();

        String typeName = switch (type) {
            case 1 -> "GlobalProperties";
            case 2 -> "DefaultDimProperties";
            case 3 -> "DimensionProperties";
            default -> "Unknown(" + type + ")";
        };

        Minecraft.getInstance().execute(() -> {
            chat("═══ JourneyMap 配置 ═══");
            chat("类型: " + typeName);
            if (!dim.isEmpty()) chat("维度: " + dim);
            if (json.length() > 200) {
                chat("配置JSON (截取前200字符):");
                chat(json.substring(0, 200) + "...");
            } else {
                chat("配置JSON: " + json);
            }
            chat("══════════════════════");
        });
    }

    // ── Inventory collection flush (call from client tick) ─────────────

    /** Called by IA03 before sending requests to enable GUI mode */
    public static void requestInventoryGui() {
        openGuiOnNext = true;
        pendingInventories.clear();
        pendingTimestamps.clear();
    }

    /** Call from client tick to flush completed inventories */
    public static void tickFlush() {
        if (pendingInventories.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (var it = pendingTimestamps.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            int vehicleId = entry.getKey();
            long firstSeen = entry.getValue();

            // Wait 300ms after first slot received before flushing
            if (now - firstSeen < 300) continue;

            TreeMap<Integer, ItemStack> slots = pendingInventories.remove(vehicleId);
            it.remove();
            if (slots == null || slots.isEmpty()) continue;

            if (openGuiOnNext) {
                openGuiOnNext = false;
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreen(
                        new VehicleInventoryScreen(vehicleId, slots)));
            } else {
                // Fallback: print to chat
                Minecraft.getInstance().execute(() -> {
                    chat("═══ 载具物品栏 #" + vehicleId + " ═══");
                    boolean hasItems = false;
                    for (var e : slots.entrySet()) {
                        ItemStack stack = e.getValue();
                        if (!stack.isEmpty()) {
                            chat("槽位" + e.getKey() + ": "
                                + stack.getItem().toString().replace("minecraft:", "")
                                + " x" + stack.getCount());
                            hasItems = true;
                        }
                    }
                    if (!hasItems) chat("(空载具)");
                    chat("═══════════════════");
                });
            }
        }
    }

    // ── Corpse MessageOpenHistory parser (disc=1) ─────────────────────
    // Wire: SimpleChannel wraps as readByte(disc) + message payload
    // MessageOpenHistory payload: CompoundTag { "Deaths": ListTag<CompoundTag> }
    // Each Death CompoundTag has: id (UUID), playerUUID, playerName, timestamp, pos, etc.
    private static void parseCorpse(ResourceLocation channel, FriendlyByteBuf buf) {
        int disc = buf.readByte();
        if (disc != 1) return; // Only intercept MessageOpenHistory (index 1)

        try {
            CompoundTag root = buf.readNbt();
            if (root == null || !root.contains("Deaths")) return;

            ListTag deathsTag = root.getList("Deaths", 10); // 10 = CompoundTag
            List<DeathRecord> records = new ArrayList<>();
            for (int i = 0; i < deathsTag.size(); i++) {
                CompoundTag tag = deathsTag.getCompound(i);
                records.add(parseDeathNBT(tag));
            }

            capturedDeaths.clear();
            capturedDeaths.addAll(records);

            // Notify listeners on main thread
            Minecraft.getInstance().execute(() -> {
                for (Consumer<List<DeathRecord>> listener : deathListeners) {
                    try { listener.accept(records); } catch (Exception e) {
                        LOGGER.error("Death listener error", e);
                    }
                }
                chat("截获 " + records.size() + " 条死亡记录");
            });
        } catch (Exception e) {
            LOGGER.error("Failed to parse Corpse MessageOpenHistory", e);
        }
    }

    private static DeathRecord parseDeathNBT(CompoundTag tag) {
        // Death ID: try new format (Id as UUID) then legacy (IdMost/IdLeast)
        UUID id;
        if (tag.contains("Id")) {
            id = tag.getUUID("Id");
        } else if (tag.contains("IdMost") && tag.contains("IdLeast")) {
            id = new UUID(tag.getLong("IdMost"), tag.getLong("IdLeast"));
        } else {
            id = new UUID(0, 0);
        }
        // Player UUID: try new format then legacy
        UUID playerUUID;
        if (tag.contains("PlayerUuid")) {
            playerUUID = tag.getUUID("PlayerUuid");
        } else if (tag.contains("PlayerUuidMost") && tag.contains("PlayerUuidLeast")) {
            playerUUID = new UUID(tag.getLong("PlayerUuidMost"), tag.getLong("PlayerUuidLeast"));
        } else {
            playerUUID = new UUID(0, 0);
        }
        String playerName = tag.getString("PlayerName");
        long timestamp = tag.getLong("Timestamp");
        double posX = tag.getDouble("PosX");
        double posY = tag.getDouble("PosY");
        double posZ = tag.getDouble("PosZ");
        String dimension = tag.getString("Dimension");
        int experience = tag.getInt("Experience");
        return new DeathRecord(id, playerUUID, playerName, timestamp, posX, posY, posZ, dimension, experience);
    }

    // ── Corpse death history public API ─────────────────────────────────

    /** Get the most recently captured death records */
    public static List<DeathRecord> getCapturedDeaths() {
        return new ArrayList<>(capturedDeaths);
    }

    /** Register a listener for death history responses */
    public static void addDeathListener(Consumer<List<DeathRecord>> listener) {
        deathListeners.add(listener);
    }

    /** Remove a death listener */
    public static void removeDeathListener(Consumer<List<DeathRecord>> listener) {
        deathListeners.remove(listener);
    }

    // ── Helpers ─────────────────────────────────────────────────────────
    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal("" + msg)
                    .withStyle(Style.EMPTY.withColor(0xFF5555)),
                false
            );
        }
    }

    // ── Pipeline injection on login ─────────────────────────────────────
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            var conn = Minecraft.getInstance().getConnection();
            if (conn == null) return;
            var channel = conn.getConnection().channel();
            channel.eventLoop().execute(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    pipeline.remove(HANDLER_NAME);
                }
                pipeline.addBefore("packet_handler", HANDLER_NAME,
                        new ResponseSniffer());
                LOGGER.info("Response sniffer injected");
            });
        } catch (Exception e) {
            LOGGER.error("Failed to inject sniffer", e);
        }
    }
}