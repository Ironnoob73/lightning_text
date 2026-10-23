package dev.hail.lightning_text.client.title;

import dev.hail.lightning_text.LightningText;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * Builds the texts shown by the banners.
 */
public final class TitleTexts {
    /** "Standard Galactic Alphabet" - the letters used by the enchanting table. */
    public static final ResourceLocation GALACTIC_FONT = ResourceLocation.withDefaultNamespace("alt");

    private static final int TICKS_PER_DAY = 24000;
    private static final int MINUTES_PER_DAY = 1440;
    /** Minecraft day time 0 is 06:00 in the morning. */
    private static final int DAY_TIME_OFFSET_MINUTES = 6 * 60;

    private TitleTexts() {
    }

    /**
     * "第X天" for the first ten days (dedicated translation keys) and "第N天" afterwards.
     */
    public static Component day(int dayNumber) {
        int day = Math.max(1, dayNumber);
        if (day <= 10) {
            return Component.translatable(LightningText.MOD_ID + ".day." + day);
        }
        return Component.translatable(LightningText.MOD_ID + ".day", Integer.toString(day));
    }

    /** Current in-game time as a 24 hour {@code HH:MM} clock. */
    public static Component clock(long dayTime) {
        long ticks = Math.floorMod(dayTime, (long) TICKS_PER_DAY);
        int minutes = (int) (ticks * MINUTES_PER_DAY / TICKS_PER_DAY);
        int hour = (minutes / 60 + DAY_TIME_OFFSET_MINUTES / 60) % 24;
        int minute = minutes % 60;
        return Component.literal(String.format(Locale.ROOT, "%02d:%02d", hour, minute));
    }

    /** Localised biome name, with a readable fallback for biomes that ship without a translation. */
    public static Component biomeName(ResourceLocation biome) {
        return registryName(biome, "biome");
    }

    /** Localised structure name, with a readable fallback for structures that ship without a translation. */
    public static Component structureName(ResourceLocation structure) {
        return registryName(structure, "structure");
    }

    /** Localised dimension name, with a readable fallback for dimensions that ship without a translation. */
    public static Component dimensionName(ResourceLocation dimension) {
        return registryName(dimension, "dimension");
    }

    /**
     * Looks up {@code <type>.<namespace>.<path>} (for example {@code structure.minecraft.village}) and
     * falls back to a beautified path. The result is uppercased like the other banners.
     */
    private static Component registryName(ResourceLocation id, String type) {
        String name = Component.translatableWithFallback(id.toLanguageKey(type), beautify(id.getPath())).getString().toUpperCase();
        return Component.literal(name);
    }

    /**
     * Renders a registry name such as {@code minecraft:plains} in the Standard Galactic Alphabet.
     *
     * <p>The vanilla galactic font only contains the letters A-Z / a-z, so separators and digits are
     * kept in the normal font; otherwise they would show up as the "missing glyph" box.
     */
    public static Component galactic(String text) {
        MutableComponent result = Component.empty();
        StringBuilder run = new StringBuilder();
        boolean runGalactic = false;
        boolean started = false;
        for (int codePoint : text.codePoints().toArray()) {
            boolean galactic = isAsciiLetter(codePoint);
            if (!started) {
                started = true;
                runGalactic = galactic;
            } else if (galactic != runGalactic) {
                appendRun(result, run, runGalactic);
                run.setLength(0);
                runGalactic = galactic;
            }
            run.appendCodePoint(codePoint);
        }
        appendRun(result, run, runGalactic);
        return result;
    }

    private static void appendRun(MutableComponent target, StringBuilder run, boolean galactic) {
        if (run.length() == 0) {
            return;
        }
        MutableComponent part = Component.literal(run.toString());
        if (galactic) {
            part.setStyle(Style.EMPTY.withFont(GALACTIC_FONT));
        } else if (run.toString().equals("_") || run.toString().equals(":"))
            part = Component.literal(" ");
        target.append(part);
    }

    private static boolean isAsciiLetter(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z');
    }

    /** {@code old_growth_pine_taiga} -> {@code Old Growth Pine Taiga}. */
    private static String beautify(String path) {
        String[] words = path.split("[_/]");
        StringBuilder builder = new StringBuilder(path.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            word = word.toUpperCase();
            if (word.length() > 1) {
                builder.append(word, 0, word.length());
            }
        }
        return builder.isEmpty() ? path : builder.toString();
    }
}
