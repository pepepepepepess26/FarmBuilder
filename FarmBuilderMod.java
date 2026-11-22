package com.example.farmbuilder;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = FarmBuilderMod.MODID, dist = Dist.CLIENT)
public class FarmBuilderMod {
    public static final String MODID = "farmbuilder";

    public FarmBuilderMod(IEventBus modBus) {
        modBus.addListener(FarmKeys::register);
    }
}
