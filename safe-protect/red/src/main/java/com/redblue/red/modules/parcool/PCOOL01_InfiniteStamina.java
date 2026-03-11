package com.redblue.red.modules.parcool;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.config.ConfigParam;
import com.redblue.red.util.PacketForge;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * PCOOL-01: Client-Authoritative Stamina Manipulation.
 *
 * SyncStaminaMessage (index 10) carries stamina state from client.
 * Server handler blindly sets OtherStamina.setMax/set/setExhaustion
 * from packet fields with zero validation, then broadcasts to ALL.
 *
 * Wire format (confirmed via javap):
 *   writeByte(10)           -- discriminator
 *   writeInt(stamina)
 *   writeInt(max)
 *   writeBoolean(exhausted)
 *   writeBoolean(imposingPenalty)
 *   writeInt(staminaType)
 *   writeInt(consumeOnServer)
 *   writeLong(uuidMost)
 *   writeLong(uuidLeast)
 */
public class PCOOL01_InfiniteStamina implements AttackModule {

    private static final Logger LOGGER = LogManager.getLogger("PCOOL01");

    private static final ResourceLocation CHANNEL =
            new ResourceLocation("parcool", "message");
    private static final int IDX_SYNC_STAMINA = 10;

    // Reflection handles for Parcool internals (resolved once, cached)
    private static boolean reflectionResolved = false;
    private static Capability<?> staminaCap = null;       // Capabilities.STAMINA_CAPABILITY
    private static RegistryObject<?> maxStaminaAttr = null; // Attributes.MAX_STAMINA
    private static Method iStaminaSet = null;              // IStamina.set(int)
    private static Method iStaminaSetExhaustion = null;    // IStamina.setExhaustion(boolean)

    private long lastTick = 0;

    private final List<ConfigParam> params = List.of(
            ConfigParam.ofBool("enabled", "启用", false),
            ConfigParam.ofInt("stamina", "体力值", 2147483647, 0, 2147483647),
            ConfigParam.ofInt("max_stamina", "最大体力", 2147483647, 0, 2147483647),
            ConfigParam.ofBool("no_exhaustion", "禁用疲劳", true),
            ConfigParam.ofInt("interval", "发包间隔(tick)", 5, 1, 200)
    );

    @Override public String id() { return "pcool01_infinite_stamina"; }
    @Override public String name() { return "无限体力"; }
    @Override public String description() { return "伪造体力同步包，设置无限体力并禁用疲劳"; }

    @Override
    public String getTutorial() {
        return "[使用方法]\n"
                + "1. 启用模块后自动每隔 N tick 发送伪造体力包\n"
                + "2. 服务端会直接接受并广播给所有玩家\n\n"
                + "[参数说明]\n"
                + "体力值/最大体力：伪造的体力数值，默认 INT_MAX\n"
                + "禁用疲劳：设置 exhausted=false, imposingPenalty=false\n"
                + "发包间隔：自动发包的 tick 间隔\n\n"
                + "[原理]\n"
                + "SyncStaminaMessage 的服务端 handler 直接调用\n"
                + "OtherStamina.set/setMax/setExhaustion，无任何校验";
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded("parcool");
    }

    @Override
    public List<ConfigParam> getConfigParams() { return params; }

    @Override
    public void execute(Minecraft mc) {
        if (mc.player == null) return;
        sendStaminaPacket(mc);
        syncClientState(mc.player);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        int interval = getParam("interval").map(ConfigParam::getInt).orElse(5);
        long now = mc.level.getGameTime();
        if (now - lastTick < interval) return;
        lastTick = now;
        sendStaminaPacket(mc);
        syncClientState(mc.player);
    }

    private void sendStaminaPacket(Minecraft mc) {
        int stamina = getParam("stamina").map(ConfigParam::getInt).orElse(Integer.MAX_VALUE);
        int maxStamina = getParam("max_stamina").map(ConfigParam::getInt).orElse(Integer.MAX_VALUE);
        boolean noExhaustion = getParam("no_exhaustion").map(ConfigParam::getBool).orElse(true);

        PacketForge.send(CHANNEL, buf -> {
            buf.writeByte(IDX_SYNC_STAMINA);
            buf.writeInt(stamina);                          // stamina
            buf.writeInt(maxStamina);                       // max
            buf.writeBoolean(!noExhaustion);                // exhausted
            buf.writeBoolean(false);                        // imposingPenalty
            buf.writeInt(-1);                               // staminaType (-1 = skip consume)
            buf.writeInt(0);                                // consumeOnServer
            buf.writeLong(mc.player.getUUID().getMostSignificantBits());
            buf.writeLong(mc.player.getUUID().getLeastSignificantBits());
        });
    }

    /**
     * Directly patch the local player's Parcool stamina capability so the
     * client-side HUD reflects our spoofed values immediately, without
     * waiting for a server echo that never comes for the sender.
     */
    private void syncClientState(Player player) {
        try {
            resolveReflection();
            if (staminaCap == null) return;

            int stamina = getParam("stamina").map(ConfigParam::getInt).orElse(Integer.MAX_VALUE);
            int maxStamina = getParam("max_stamina").map(ConfigParam::getInt).orElse(Integer.MAX_VALUE);
            boolean noExhaustion = getParam("no_exhaustion").map(ConfigParam::getBool).orElse(true);

            // 1) Bump the MAX_STAMINA attribute so set() won't clamp our value
            if (maxStaminaAttr != null) {
                Object attr = maxStaminaAttr.get(); // Attribute instance
                if (attr instanceof net.minecraft.world.entity.ai.attributes.Attribute attribute) {
                    AttributeInstance inst = player.getAttribute(attribute);
                    if (inst != null) {
                        inst.setBaseValue(maxStamina);
                    }
                }
            }

            // 2) Get the IStamina capability from the player
            @SuppressWarnings("unchecked")
            Capability<Object> cap = (Capability<Object>) staminaCap;
            Object iStamina = player.getCapability(cap).orElse(null);
            if (iStamina == null) return;

            // 3) Set stamina value
            if (iStaminaSet != null) {
                iStaminaSet.invoke(iStamina, stamina);
            }

            // 4) Set exhaustion state
            if (iStaminaSetExhaustion != null) {
                iStaminaSetExhaustion.invoke(iStamina, !noExhaustion);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to sync client stamina state", e);
        }
    }

    /** Resolve Parcool reflection handles once. */
    @SuppressWarnings("unchecked")
    private static synchronized void resolveReflection() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            // Capabilities.STAMINA_CAPABILITY
            Class<?> capClass = Class.forName(
                    "com.alrex.parcool.common.capability.capabilities.Capabilities");
            Field capField = capClass.getDeclaredField("STAMINA_CAPABILITY");
            capField.setAccessible(true);
            staminaCap = (Capability<?>) capField.get(null);

            // Attributes.MAX_STAMINA
            Class<?> attrClass = Class.forName("com.alrex.parcool.api.Attributes");
            Field maxField = attrClass.getDeclaredField("MAX_STAMINA");
            maxField.setAccessible(true);
            maxStaminaAttr = (RegistryObject<?>) maxField.get(null);

            // IStamina.set(int) and IStamina.setExhaustion(boolean)
            Class<?> iStaminaClass = Class.forName(
                    "com.alrex.parcool.common.capability.IStamina");
            iStaminaSet = iStaminaClass.getMethod("set", int.class);
            iStaminaSetExhaustion = iStaminaClass.getMethod("setExhaustion", boolean.class);

            LOGGER.debug("Parcool reflection resolved successfully");
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve Parcool reflection handles", e);
        }
    }
}
