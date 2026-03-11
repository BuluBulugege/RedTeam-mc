package com.redblue.red.modules.curios;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * CURIOS-01: Unconditional Curios inventory destruction.
 *
 * CPacketDestroy (index 3) has an empty payload. The server-side handler
 * (lambda$handle$5) only checks sender != null, then iterates ALL curios
 * slot types and sets every equipment + cosmetic slot to ItemStack.EMPTY.
 * Items are permanently deleted (not dropped). Attribute modifiers are removed.
 *
 * Verified via javap:
 *   - encode(): empty method body (just return)
 *   - decode(): new CPacketDestroy() with no fields
 *   - handler: getSender() -> null check -> getCuriosInventory -> forEach setStackInSlot(EMPTY)
 *   - NO creative mode check
 *   - NO container/GUI open check
 *   - NO isAlive check
 *   - NO permission check
 *
 * Wire format:
 *   buf.writeByte(3)  // discriminator only, no payload
 *
 * NetworkHandler.id starts at 0 (iconst_0 in static{}), registration order:
 *   0=OpenCurios, 1=OpenVanilla, 2=Scroll, 3=Destroy, 4=ToggleRender, ...
 */
public class CURIOS01_DestroyAll implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("curios", "main");
    private static final int IDX_DESTROY = 3;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger", "确认: 将永久删除所有饰品", false),
        ConfigParam.ofInt("packet_count", "发包次数", 1, 1, 5)
    );

    @Override public String id() { return "curios01_destroyall"; }
    @Override public String name() { return "清空饰品"; }
    @Override public String description() { return "发送空包永久删除自身所有Curios饰品"; }

    @Override
    public String getTutorial() {
        return "[漏洞原理]\n"
            + "CPacketDestroy 是 Curios 的创造模式清空功能，\n"
            + "但服务端 handler 没有检查创造模式、GUI状态或任何权限。\n"
            + "任何在线玩家发送一个空包即可清空自己所有饰品槽。\n"
            + "物品直接消失，不会掉落。\n\n"
            + "[使用方法]\n"
            + "1. 勾选「确认」复选框\n"
            + "2. 点击「执行」\n"
            + "3. 你身上所有 Curios 饰品将被永久删除\n\n"
            + "[注意事项]\n"
            + "此操作不可逆！物品不会掉落，直接从服务端删除。\n"
            + "仅影响自己的饰品，无法影响其他玩家。\n"
            + "可用于红队演习中演示「恶意客户端自毁装备」场景。";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("curios");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;

        boolean confirmed = getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false);
        if (!confirmed) return;

        int count = getParam("packet_count")
                .map(ConfigParam::getInt).orElse(1);

        for (int i = 0; i < count; i++) {
            PacketForge.send(CHANNEL, buf -> {
                buf.writeByte(IDX_DESTROY);
                // Empty payload - encode() is a no-op
            });
        }
    }
}
