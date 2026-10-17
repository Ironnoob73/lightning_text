package dev.hail.lightning_text.client.title;

import dev.hail.lightning_text.client.config.LTConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Holds the single banner that is currently on screen plus a small queue of pending banners.
 *
 * <p>Command titles (vanilla {@code /title}) take priority: they cancel anything that is queued and
 * are shown immediately. Day / biome banners are queued so that two events firing in the same tick
 * (for example dawn while crossing a biome border) are shown one after another instead of
 * overwriting each other.
 */
public final class TitleManager {
    /** Vanilla keeps 70 ticks of "stay" time by default, day/biome banners use that value as-is. */
    public static final int DEFAULT_STAY_TICKS = 70;
    private static final int MAX_QUEUE = 4;

    public enum Kind {
        COMMAND,
        DAY,
        BIOME
    }

    public enum SubtitleStyle {
        /** Rendered with the system font (day clock, command subtitles). */
        SYSTEM,
        /** Rendered with the vanilla "Standard Galactic Alphabet" font (biome registry names). */
        GALACTIC
    }

    public static final class Entry {
        @Nullable
        private Component main;
        @Nullable
        private Component sub;
        private SubtitleStyle subStyle;
        private final int fadeIn;
        private final int stay;
        private final int fadeOut;
        private final Kind kind;
        private int age;

        private Entry(@Nullable Component main, @Nullable Component sub, SubtitleStyle subStyle,
                int fadeIn, int stay, int fadeOut, Kind kind) {
            this.main = main;
            this.sub = sub;
            this.subStyle = subStyle;
            this.fadeIn = Math.max(0, fadeIn);
            this.stay = Math.max(0, stay);
            this.fadeOut = Math.max(0, fadeOut);
            this.kind = kind;
            this.age = 0;
        }

        @Nullable
        public Component main() {
            return this.main;
        }

        @Nullable
        public Component sub() {
            return this.sub;
        }

        public SubtitleStyle subStyle() {
            return this.subStyle;
        }

        public Kind kind() {
            return this.kind;
        }

        public int age() {
            return this.age;
        }

        public int totalTicks() {
            return Math.max(1, this.fadeIn + this.stay + this.fadeOut);
        }

        /** 0..1 opacity for the given (partial tick corrected) age. */
        public float alpha(float partialAge) {
            float total = this.totalTicks();
            if (partialAge <= 0.0F) {
                return this.fadeIn > 0 ? 0.0F : 1.0F;
            }
            if (partialAge >= total) {
                return 0.0F;
            }
            float alpha = 1.0F;
            if (this.fadeIn > 0 && partialAge < this.fadeIn) {
                alpha = partialAge / this.fadeIn;
            }
            if (this.fadeOut > 0) {
                float remaining = total - partialAge;
                if (remaining < this.fadeOut) {
                    alpha = Math.min(alpha, remaining / this.fadeOut);
                }
            }
            return Math.max(0.0F, Math.min(1.0F, alpha));
        }
    }

    private static final Deque<Entry> QUEUE = new ArrayDeque<>();
    @Nullable
    private static Entry current;

    // Mirrors the vanilla Gui fields so that "/title ..." packets behave exactly like vanilla.
    @Nullable
    private static Component lastTitle;
    @Nullable
    private static Component lastSubtitle;
    private static int commandFadeIn = 10;
    private static int commandStay = 70;
    private static int commandFadeOut = 20;

    private TitleManager() {
    }

    @Nullable
    public static Entry current() {
        return current;
    }

    // ------------------------------------------------------------------ ticking

    public static void onClientTick(ClientTickEvent.Post event) {
        if (current != null) {
            current.age++;
            if (current.age >= current.totalTicks()) {
                if (current.kind == Kind.COMMAND) {
                    // Vanilla drops both texts when the title timer runs out.
                    lastTitle = null;
                    lastSubtitle = null;
                }
                current = null;
            }
        }
        if (current == null) {
            startNext();
        }
    }

    private static void startNext() {
        while (current == null && !QUEUE.isEmpty()) {
            current = QUEUE.poll();
        }
        if (current != null && current.kind != Kind.COMMAND) {
            playThunder();
        }
    }

    private static void playThunder() {
        if (!LTConfig.titleSoundEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        // Pitch 0 (Minecraft clamps it to 0.5) gives the low, drawn out rumble used for these banners.
        level.playLocalSound(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.0F, 0.0F, false);
    }

    // ------------------------------------------------------------------ command titles

    public static void onCommandTitle(Component title) {
        lastTitle = title;
        QUEUE.clear();
        current = new Entry(lastTitle, lastSubtitle, SubtitleStyle.SYSTEM,
                commandFadeIn, commandStay, commandFadeOut, Kind.COMMAND);
    }

    public static void onCommandSubtitle(Component subtitle) {
        lastSubtitle = subtitle;
        if (current != null && current.kind == Kind.COMMAND) {
            current.sub = subtitle;
            current.subStyle = SubtitleStyle.SYSTEM;
        } else {
            QUEUE.clear();
            current = new Entry(lastTitle, lastSubtitle, SubtitleStyle.SYSTEM,
                    commandFadeIn, commandStay, commandFadeOut, Kind.COMMAND);
        }
    }

    public static void onCommandTimes(int fadeIn, int stay, int fadeOut) {
        if (fadeIn >= 0) {
            commandFadeIn = fadeIn;
        }
        if (stay >= 0) {
            commandStay = stay;
        }
        if (fadeOut >= 0) {
            commandFadeOut = fadeOut;
        }
        if (current != null && current.kind == Kind.COMMAND) {
            current.age = 0;
        }
    }

    public static void onCommandResetTimes() {
        commandFadeIn = 10;
        commandStay = 70;
        commandFadeOut = 20;
    }

    public static void clear() {
        QUEUE.clear();
        current = null;
        lastTitle = null;
        lastSubtitle = null;
    }

    // ------------------------------------------------------------------ automated banners

    public static void showDay(Component dayText, Component clockText) {
        if (!LTConfig.dayTitleEnabled()) {
            return;
        }
        enqueue(new Entry(dayText, clockText, SubtitleStyle.SYSTEM, 0, DEFAULT_STAY_TICKS, 0, Kind.DAY));
    }

    public static void showBiome(Component biomeName, Component galacticName) {
        if (!LTConfig.biomeTitleEnabled()) {
            return;
        }
        enqueue(new Entry(biomeName, galacticName, SubtitleStyle.GALACTIC, 0, DEFAULT_STAY_TICKS, 0, Kind.BIOME));
    }

    private static void enqueue(Entry entry) {
        if (QUEUE.size() >= MAX_QUEUE) {
            return;
        }
        QUEUE.add(entry);
        if (current == null) {
            startNext();
        }
    }
}
