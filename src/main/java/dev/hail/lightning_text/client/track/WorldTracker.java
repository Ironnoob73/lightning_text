package dev.hail.lightning_text.client.track;

import dev.hail.lightning_text.client.config.LTConfig;
import dev.hail.lightning_text.client.store.VisitedStore;
import dev.hail.lightning_text.client.title.TitleManager;
import dev.hail.lightning_text.client.title.TitleTexts;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Watches the client world for "a new day started", "a biome was entered for the first time" and
 * "a dimension was entered for the first time". Entirely client side: it only reads the synced day
 * time, the biome holder at the player's position and the dimension key, nothing is ever sent to
 * the server.
 *
 * <p>The set of already visited biomes / dimensions is persisted per world / server by
 * {@link VisitedStore}.
 */
public final class WorldTracker {
    private static final long TICKS_PER_DAY = 24000L;

    @Nullable
    private static ClientLevel lastLevel;
    private static long lastDay = -1L;
    @Nullable
    private static ResourceLocation lastBiome;
    @Nullable
    private static ResourceLocation pendingBiome;
    private static int pendingTicks;

    private WorldTracker() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            if (lastLevel != null) {
                reset();
            }
            return;
        }
        if (level != lastLevel) {
            onLevelChanged(minecraft, level);
        }
        tickDay(level);
        tickBiome(level, player);
    }

    private static void onLevelChanged(Minecraft minecraft, ClientLevel level) {
        lastLevel = level;
        lastDay = dayOf(level);
        lastBiome = null;
        pendingBiome = null;
        pendingTicks = 0;
        // The key stays the same when only the dimension changed, so nothing is reloaded then.
        VisitedStore.open(minecraft);
        announceDimension(level);
    }

    /**
     * Shows the dimension banner the first time this dimension is visited in the current world/server.
     * Called for every level change, so joining a world counts as entering its initial dimension.
     */
    private static void announceDimension(ClientLevel level) {
        if (!LTConfig.dimensionTitleEnabled()) {
            // Disabled features record nothing, so enabling them later still works.
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        if (!VisitedStore.markDimension(dimension)) {
            return;
        }
        TitleManager.showDimension(TitleTexts.dimensionName(dimension), TitleTexts.galactic(dimension.toString()));
    }

    private static void reset() {
        lastLevel = null;
        lastDay = -1L;
        lastBiome = null;
        pendingBiome = null;
        pendingTicks = 0;
        VisitedStore.close();
        TitleManager.clear();
    }

    private static void tickDay(ClientLevel level) {
        long day = dayOf(level);
        if (lastDay < 0L) {
            lastDay = day;
            return;
        }
        if (day > lastDay) {
            lastDay = day;
            // Minecraft counts from day 0, players count from day 1.
            long displayDay = Math.min(Integer.MAX_VALUE, day + 1L);
            TitleManager.showDay(TitleTexts.day((int) displayDay), TitleTexts.clock(level.getDayTime()));
        } else if (day < lastDay) {
            // Time was moved backwards (/time set, or a server resync): stay quiet and resync.
            lastDay = day;
        }
    }

    private static long dayOf(ClientLevel level) {
        return Math.floorDiv(level.getDayTime(), TICKS_PER_DAY);
    }

    private static void tickBiome(ClientLevel level, Player player) {
        ResourceLocation biome = biomeId(level, player.blockPosition());
        if (biome == null) {
            return;
        }
        if (!biome.equals(lastBiome)) {
            lastBiome = biome;
            pendingBiome = biome;
            pendingTicks = 0;
            return;
        }
        if (pendingBiome == null) {
            // Already handled: either announced, or it had been visited before.
            return;
        }
        if (++pendingTicks < Math.max(0, LTConfig.biomeDetectDelay())) {
            return;
        }
        pendingBiome = null;
        if (!LTConfig.biomeTitleEnabled()) {
            // Disabled features record nothing, so enabling them later still works.
            return;
        }
        if (!VisitedStore.markBiome(biome)) {
            return;
        }
        TitleManager.showBiome(TitleTexts.biomeName(biome), TitleTexts.galactic(biome.toString()));
    }

    @Nullable
    private static ResourceLocation biomeId(ClientLevel level, BlockPos pos) {
        Holder<Biome> holder = level.getBiome(pos);
        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        if (key != null) {
            return key.location();
        }
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        return registry.getKey(holder.value());
    }
}
