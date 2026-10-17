package dev.hail.lightning_text.client.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration. Everything here is purely visual, so it lives in the per-instance
 * {@code config/lightning_text-client.toml} file (editable in the mod list screen as well).
 */
public final class LTConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DAY_TITLE = BUILDER
            .comment("Show the big day banner (\"第X天\" / \"DAY X\") whenever a new in-game day begins.")
            .define("enableDayTitle", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BIOME_TITLE = BUILDER
            .comment("Show the big banner the first time the player enters a biome.")
            .define("enableBiomeTitle", true);

    public static final ModConfigSpec.BooleanValue ENABLE_TITLE_SOUND = BUILDER
            .comment("Play minecraft:entity.lightning_bolt.thunder (pitch 0) when a day or biome banner appears.",
                    "Command titles never play a sound.")
            .define("enableTitleSound", true);

    public static final ModConfigSpec.ConfigValue<String> FONT_PATH = BUILDER
            .comment("Path of the TrueType font used for the banners. Empty means auto-detect.",
                    "On Windows this is normally C:/Windows/Fonts/simhei.ttf (SimHei, 黑体).")
            .define("fontPath", "");

    public static final ModConfigSpec.DoubleValue TITLE_SCALE = BUILDER
            .comment("Multiplier applied to the maximum banner size.",
                    "Banners still shrink automatically when the text would not fit on screen.")
            .defineInRange("titleScale", 2.5D, 0.25D, 3.0D);

    public static final ModConfigSpec.IntValue BIOME_DETECT_DELAY = BUILDER
            .comment("Ticks the player has to stay inside a biome before its banner is shown.",
                    "Prevents banners from flickering while walking along biome borders.")
            .defineInRange("biomeDetectDelay", 20, 0, 200);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private LTConfig() {
    }

    public static boolean dayTitleEnabled() {
        return read(() -> ENABLE_DAY_TITLE.get(), true);
    }

    public static boolean biomeTitleEnabled() {
        return read(() -> ENABLE_BIOME_TITLE.get(), true);
    }

    public static boolean titleSoundEnabled() {
        return read(() -> ENABLE_TITLE_SOUND.get(), true);
    }

    public static String fontPath() {
        return read(() -> FONT_PATH.get(), "");
    }

    public static double titleScale() {
        return read(() -> TITLE_SCALE.get(), 1.0D);
    }

    public static int biomeDetectDelay() {
        return read(() -> BIOME_DETECT_DELAY.get(), 20);
    }

    /**
     * Config values are only readable once NeoForge has loaded the file. Rendering may in theory
     * run before that (or after a failed load), so every accessor degrades to its default instead
     * of throwing.
     */
    private static <T> T read(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
