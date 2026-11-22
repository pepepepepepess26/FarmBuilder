package com.example.farmbuilder;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public final class FarmKeys {
    /** Пауза / продолжить стройку (по умолчанию K, меняется в настройках управления). */
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.farmbuilder.farm_toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_K, "Farm Builder");

    private FarmKeys() {}

    public static void register(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
    }
}
