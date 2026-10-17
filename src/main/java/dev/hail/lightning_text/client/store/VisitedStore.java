package dev.hail.lightning_text.client.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hail.lightning_text.LightningText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Client side record of everything the player already discovered in the current world or server.
 *
 * <p>One small JSON file per world / server is written below
 * {@code <game directory>/lightning_text/visited/}. Single player worlds are keyed by their save
 * folder name, servers by their address, so the data survives game restarts and is never shared
 * between two different worlds.
 *
 * <p>Nothing here touches the network or the server: it is the client remembering what it has seen,
 * exactly like a minimap mod remembers explored chunks.
 */
public final class VisitedStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ROOT_FOLDER = "lightning_text";
    private static final String VISITED_FOLDER = "visited";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_NAME = 96;

    private static final Set<ResourceLocation> BIOMES = new LinkedHashSet<>();
    private static final Set<ResourceLocation> BOSSES = new LinkedHashSet<>();

    @Nullable
    private static String key;
    @Nullable
    private static Path file;
    private static boolean dirty;

    private VisitedStore() {
    }

    /**
     * Points the store at the world/server that is currently being played. Does nothing when the
     * key did not change (for example on a dimension change).
     */
    public static void open(Minecraft minecraft) {
        String newKey = computeKey(minecraft);
        if (newKey.equals(key)) {
            return;
        }
        save();
        key = newKey;
        file = resolveFile(minecraft, newKey);
        load();
        LightningText.LOGGER.debug("Lightning Text now recording visits for '{}' in {}", key, file);
    }

    /** Writes pending changes and detaches from the current world/server. */
    public static void close() {
        save();
        key = null;
        file = null;
        BIOMES.clear();
        BOSSES.clear();
        dirty = false;
    }

    /**
     * @return {@code true} when this biome had never been seen in this world/server before.
     */
    public static boolean markBiome(ResourceLocation biome) {
        if (key == null || !BIOMES.add(biome)) {
            return false;
        }
        dirty = true;
        save();
        return true;
    }

    /**
     * @return {@code true} when this boss had never been fought in this world/server before.
     */
    public static boolean markBoss(ResourceLocation boss) {
        if (key == null || !BOSSES.add(boss)) {
            return false;
        }
        dirty = true;
        save();
        return true;
    }

    // ------------------------------------------------------------------ world/server identity

    private static String computeKey(Minecraft minecraft) {
        IntegratedServer singleplayer = minecraft.getSingleplayerServer();
        if (singleplayer != null) {
            try {
                // LevelResource.ROOT resolves to a path ending in ".", so normalise it first.
                Path root = singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                Path name = root.getFileName();
                if (name != null) {
                    String folder = name.toString();
                    if (!folder.isBlank() && !folder.equals(".") && !folder.equals("..")) {
                        return "world:" + folder;
                    }
                }
            } catch (RuntimeException exception) {
                LightningText.LOGGER.warn("Could not determine the singleplayer world name", exception);
            }
            return "world:unknown";
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "server:" + server.ip;
        }
        return "unknown";
    }

    private static Path resolveFile(Minecraft minecraft, String worldKey) {
        String sanitised = worldKey.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (sanitised.length() > MAX_FILE_NAME) {
            sanitised = sanitised.substring(0, MAX_FILE_NAME);
        }
        // The hash keeps two keys that sanitise to the same text apart.
        String fileName = sanitised + "." + Integer.toHexString(worldKey.hashCode()) + ".json";
        return minecraft.gameDirectory.toPath()
                .resolve(ROOT_FOLDER)
                .resolve(VISITED_FOLDER)
                .resolve(fileName);
    }

    // ------------------------------------------------------------------ disk IO

    private static void load() {
        BIOMES.clear();
        BOSSES.clear();
        dirty = false;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Data data = GSON.fromJson(json, Data.class);
            if (data == null) {
                return;
            }
            readIds(data.biomes, BIOMES);
            readIds(data.bosses, BOSSES);
        } catch (Exception exception) {
            LightningText.LOGGER.warn("Failed to read the visit record {}, starting a new one", file, exception);
        }
    }

    private static void readIds(@Nullable List<String> raw, Set<ResourceLocation> target) {
        if (raw == null) {
            return;
        }
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id != null) {
                target.add(id);
            }
        }
    }

    private static void save() {
        if (!dirty || file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Data data = new Data();
            data.format = FORMAT_VERSION;
            data.key = key;
            data.biomes = toStrings(BIOMES);
            data.bosses = toStrings(BOSSES);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            LightningText.LOGGER.warn("Failed to write the visit record {}", file, exception);
        }
    }

    private static List<String> toStrings(Set<ResourceLocation> ids) {
        List<String> result = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            result.add(id.toString());
        }
        return result;
    }

    /** Serialisation layout (kept intentionally tiny and human readable). */
    private static final class Data {
        private int format = FORMAT_VERSION;
        @Nullable
        private String key;
        @Nullable
        private List<String> biomes;
        @Nullable
        private List<String> bosses;
    }
}
