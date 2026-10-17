package dev.hail.lightning_text.client.track;

import dev.hail.lightning_text.client.config.LTConfig;
import dev.hail.lightning_text.client.title.TitleManager;
import dev.hail.lightning_text.client.title.TitleTexts;
import java.util.HashSet;
import java.util.Set;
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
 * Watches the client world for "a new day started" and "a biome was entered for the first time".
 * Entirely client side: it only reads the synced day time and the biome holder at the player's
 * position, nothing is ever sent to the server.
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
    private static final Set<ResourceLocation> VISITED_BIOMES = new HashSet<>();

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
            onLevelChanged(level);
        }
        tickDay(level);
        tickBiome(level, player);
    }

    private static void onLevelChanged(ClientLevel level) {
        lastLevel = level;
        lastDay = dayOf(level);
        lastBiome = null;
        pendingBiome = null;
        pendingTicks = 0;
    }

    private static void reset() {
        lastLevel = null;
        lastDay = -1L;
        lastBiome = null;
        pendingBiome = null;
        pendingTicks = 0;
        VISITED_BIOMES.clear();
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
        if (!VISITED_BIOMES.add(biome)) {
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
