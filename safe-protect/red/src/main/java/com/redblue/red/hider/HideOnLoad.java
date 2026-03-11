package com.redblue.red.hider;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HideOnLoad {

    private static final Logger LOG = LoggerFactory.getLogger("ModCompat");
    private static final String TARGET = "redteam";

    @SubscribeEvent
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            ModHider.hide(TARGET);
            HandshakeHider.hide(TARGET);
            LOG.debug("compat cleanup done");
        });
    }
}
