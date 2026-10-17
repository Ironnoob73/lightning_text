package dev.hail.lightning_text.client;

import dev.hail.lightning_text.client.config.LTConfig;
import dev.hail.lightning_text.client.font.SystemFontManager;
import dev.hail.lightning_text.client.title.TitleManager;
import dev.hail.lightning_text.client.track.WorldTracker;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client side bootstrap. Nothing in this package is ever touched on a dedicated server, because
 * the mod itself is annotated with {@code @Mod(dist = Dist.CLIENT)}.
 */
public final class LightningTextClient {
    private LightningTextClient() {
    }

    public static void init(net.neoforged.bus.api.IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, LTConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Read the system font while the loading screen is still up instead of when the first banner
        // appears. Purely file IO, no OpenGL involved.
        SystemFontManager.preload();

        // Game bus listeners (client ticks).
        NeoForge.EVENT_BUS.addListener(TitleManager::onClientTick);
        NeoForge.EVENT_BUS.addListener(WorldTracker::onClientTick);
    }
}
