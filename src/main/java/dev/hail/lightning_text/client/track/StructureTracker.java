package dev.hail.lightning_text.client.track;

import dev.hail.lightning_text.LightningText;
import dev.hail.lightning_text.client.config.LTConfig;
import dev.hail.lightning_text.client.servux.ServuxStructures;
import dev.hail.lightning_text.client.store.VisitedStore;
import dev.hail.lightning_text.client.title.TitleManager;
import dev.hail.lightning_text.client.title.TitleTexts;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Shows a banner the first time the player enters a structure in a world / server.
 *
 * <p>Vanilla does not send structure data to clients (the chunk packet only contains blocks,
 * heightmaps and block entities). In singleplayer the integrated server is queried directly; on a
 * remote server the optional Servux integration is used, and without Servux the tracker silently
 * does nothing.
 *
 * <p>The integrated-server lookup runs on the server thread so that the structure manager is never
 * touched from the client thread; the result is handed back to the client thread before anything is
 * recorded or shown.
 */
public final class StructureTracker {
    /** Structure lookups are cheap but not free, so they do not need to happen every tick. */
    private static final int SCAN_INTERVAL = 10;

    private static int scanCooldown;
    private static boolean queryInFlight;
    private static int generation;
    @Nullable
    private static ResourceLocation pendingStructure;

    private StructureTracker() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        boolean enabled = LTConfig.structureTitleEnabled();
        // Singleplayer already has the integrated server's structure manager, so Servux is only
        // needed for remote servers.
        ServuxStructures.tick(minecraft, level, enabled && minecraft.getSingleplayerServer() == null);
        if (!enabled) {
            resetQuery();
            return;
        }
        if (level == null || player == null) {
            resetQuery();
            return;
        }
        if (ServuxStructures.hasData()) {
            resetQuery();
            ResourceLocation structure = ServuxStructures.structureAt(player.blockPosition());
            if (structure != null) {
                announce(structure);
            }
            return;
        }
        if (pendingStructure != null) {
            ResourceLocation structure = pendingStructure;
            pendingStructure = null;
            announce(structure);
        }
        if (queryInFlight) {
            return;
        }
        if (++scanCooldown < SCAN_INTERVAL) {
            return;
        }
        scanCooldown = 0;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            // Remote server without Servux: no structure data is available.
            return;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null) {
            return;
        }
        BlockPos pos = player.blockPosition();
        queryInFlight = true;
        int queryId = ++generation;
        ClientLevel queryLevel = level;
        server.execute(() -> {
            ResourceLocation found = find(serverLevel, pos);
            minecraft.execute(() -> {
                queryInFlight = false;
                if (minecraft.level == queryLevel && queryId == generation && found != null) {
                    pendingStructure = found;
                }
            });
        });
    }

    private static void announce(ResourceLocation structure) {
        if (!VisitedStore.markStructure(structure)) {
            return;
        }
        LightningText.LOGGER.debug("Structure banner for {}", structure);
        TitleManager.showStructure(TitleTexts.structureName(structure), TitleTexts.galactic(structure.toString()));
    }

    /** Runs on the server thread. */
    @Nullable
    private static ResourceLocation find(ServerLevel level, BlockPos pos) {
        try {
            StructureManager manager = level.structureManager();
            Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Structure structure : manager.getAllStructuresAt(pos).keySet()) {
                StructureStart start = manager.getStructureAt(pos, structure);
                if (!start.isValid()) {
                    continue;
                }
                ResourceLocation id = registry.getKey(structure);
                if (id != null) {
                    return id;
                }
            }
        } catch (RuntimeException exception) {
            LightningText.LOGGER.warn("Could not determine the structure at {}", pos, exception);
        }
        return null;
    }

    private static void resetQuery() {
        scanCooldown = 0;
        queryInFlight = false;
        generation++;
        pendingStructure = null;
    }
}
