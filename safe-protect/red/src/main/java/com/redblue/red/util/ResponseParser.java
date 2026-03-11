package com.redblue.red.util;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Functional interface for parsing intercepted S2C custom payload responses.
 */
@FunctionalInterface
public interface ResponseParser {
    void parse(ResourceLocation channel, FriendlyByteBuf buf);
}
