package dev.hail.lightning_text;

import com.mojang.logging.LogUtils;
import dev.hail.lightning_text.client.LightningTextClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Lightning Text - client only, replaces the vanilla {@code /title} display with the kind of
 * oversized region banners used by games such as Control or Arknights: Endfield, and adds
 * day counter / first-time-biome banners.
 */
@Mod(value = LightningText.MOD_ID, dist = Dist.CLIENT)
public class LightningText {
    public static final String MOD_ID = "lightning_text";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LightningText(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist.isClient()) {
            LightningTextClient.init(modEventBus, modContainer);
        }
    }
}
