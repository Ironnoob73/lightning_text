package dev.hail.lightning_text.client.title;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.hail.lightning_text.LightningText;
import dev.hail.lightning_text.client.config.LTConfig;
import dev.hail.lightning_text.client.font.SystemFont;
import dev.hail.lightning_text.client.font.SystemFontManager;
import javax.annotation.Nullable;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Draws the banner: a very large system font line and an optional smaller subtitle.
 *
 * <p>Sizing rules: start from ~17% of the screen height, shrink when the text would leave the
 * screen, but never go below the size of a vanilla title (4x the 8px bitmap font). The glyphs are
 * baked at their final pixel size and drawn with integer coordinates so nearest neighbour filtering
 * keeps them sharp.
 */
public final class TitleRenderer {
    /** Glyphs are baked at this size first; width scales linearly, so it is used for measuring. */
    private static final float REFERENCE_SIZE = 48.0F;
    /** Roughly the height of a vanilla title (8px font * 4). */
    private static final float MIN_SIZE = 40.0F;
    private static final float BASE_SIZE_FACTOR = 0.17F;
    private static final float MAX_WIDTH_FACTOR = 0.92F;
    private static final float CENTER_Y_FACTOR = 0.42F;
    /** Vertical offset between the baseline and the top of a line in Minecraft's font space. */
    private static final float BASELINE = 7.0F;
    private static final float VANILLA_GLYPH_HEIGHT = 8.0F;
    /** Extra space between characters, as a fraction of the pixel size. */
    private static final float LETTER_SPACING = 0.04F;
    /** Size of the galactic alphabet subtitle relative to the main title. */
    private static final float SGA_SIZE_FACTOR = 0.38F;

    private static boolean reportedError;

    private TitleRenderer() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (reportedError) {
            return;
        }
        try {
            renderBanner(graphics, deltaTracker);
        } catch (Throwable throwable) {
            reportedError = true;
            LightningText.LOGGER.error("Failed to render a banner, disabling further render attempts", throwable);
        }
    }

    private static void renderBanner(GuiGraphics graphics, DeltaTracker deltaTracker) {
        TitleManager.Entry entry = TitleManager.current();
        if (entry == null) {
            return;
        }
        float age = entry.age() + deltaTracker.getGameTimeDeltaPartialTick(false);
        float alpha = entry.alpha(age);
        if (alpha <= 0.02F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font vanillaFont = minecraft.font;
        if (vanillaFont == null) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int color = FastColor.ARGB32.color(Math.round(alpha * 255.0F), 0xFFFFFF);
        int shadowColor = FastColor.ARGB32.color(Math.round(alpha * 130.0F), 0x000000);
        float centerY = height * CENTER_Y_FACTOR;
        float maxWidth = width * MAX_WIDTH_FACTOR;
        int oversample = currentOversample();

        MainLayout main = entry.main() == null ? null : layoutMain(entry.main(), height, maxWidth, oversample);
        float subtitleCenterY = centerY;
        if (main != null) {
            drawMain(graphics, main, width, centerY, color, shadowColor);
            subtitleCenterY = centerY + main.halfHeight() + main.size() * 0.32F;
        } else if (entry.main() != null) {
            // No usable system font: keep the vanilla proportions instead of showing nothing.
            drawVanillaScaled(graphics, vanillaFont, entry.main(), width * 0.5F, centerY, 4.0F, color);
            subtitleCenterY = centerY + 36.0F;
        }

        Component subtitle = entry.sub();
        if (subtitle == null) {
            return;
        }
        if (entry.subStyle() == TitleManager.SubtitleStyle.GALACTIC) {
            drawGalactic(graphics, vanillaFont, subtitle, width, maxWidth, subtitleCenterY, main, color);
        } else if (main != null) {
            drawSystemSubtitle(graphics, subtitle, width, subtitleCenterY, oversample, color, shadowColor, main);
        } else {
            drawVanillaScaled(graphics, vanillaFont, subtitle, width * 0.5F, subtitleCenterY, 2.0F, color);
        }
    }

    // ------------------------------------------------------------------ layout

    @Nullable
    private static MainLayout layoutMain(Component text, int screenHeight, float maxWidth, int oversample) {
        FormattedCharSequence sequence = text.getVisualOrderText();
        SystemFont reference = SystemFontManager.get(quantise(REFERENCE_SIZE), oversample);
        if (reference == null) {
            return null;
        }
        // Width grows linearly with the rasterised pixel size, so one measurement is enough. Letter
        // spacing scales with the size as well, hence the second term.
        int glyphs = count(sequence);
        float widthAtReference = measure(reference.font(), sequence, 0.0F)
                + reference.pixelSize() * LETTER_SPACING * Math.max(0, glyphs - 1);
        float widthPerPixel = widthAtReference / reference.pixelSize();
        float baseSize = Math.max(8.0F, screenHeight * BASE_SIZE_FACTOR * (float) LTConfig.titleScale());
        float minSize = Math.min(MIN_SIZE, baseSize);
        float target = baseSize;
        if (widthPerPixel > 0.0F && widthPerPixel * target > maxWidth) {
            target = maxWidth / widthPerPixel;
        }
        target = Mth.clamp(target, minSize, baseSize);

        SystemFont font = SystemFontManager.get(quantise(target), oversample);
        if (font == null) {
            return null;
        }
        float spacing = font.pixelSize() * LETTER_SPACING;
        float width = measure(font.font(), sequence, spacing);
        float halfHeight = (font.ascender() - font.descender()) * 0.5F;
        return new MainLayout(font, sequence, font.pixelSize(), width, spacing, halfHeight);
    }

    private static void drawMain(GuiGraphics graphics, MainLayout layout, int screenWidth, float centerY, int color,
            int shadowColor) {
        SystemFont font = layout.font();
        float y = centerY - BASELINE + (font.ascender() + font.descender()) * 0.5F;
        int x = Math.round((screenWidth - layout.width()) * 0.5F);
        int yOffset = Math.round(y);
        int shadowOffset = Math.max(1, Math.round(layout.size() * 0.05F));
        drawSpaced(graphics, font.font(), layout.text(), x + shadowOffset, yOffset + shadowOffset, shadowColor,
                layout.spacing());
        drawSpaced(graphics, font.font(), layout.text(), x, yOffset, color, layout.spacing());
    }

    private static void drawSystemSubtitle(GuiGraphics graphics, Component text, int screenWidth, float centerY,
            int oversample, int color, int shadowColor, MainLayout main) {
        FormattedCharSequence sequence = text.getVisualOrderText();
        SystemFont font = SystemFontManager.get(quantise(Math.max(12.0F, main.size() * 0.42F)), oversample);
        if (font == null) {
            drawVanillaScaled(graphics, Minecraft.getInstance().font, text, screenWidth * 0.5F, centerY, 2.0F, color);
            return;
        }
        float spacing = font.pixelSize() * 0.06F;
        float width = measure(font.font(), sequence, spacing);
        float y = centerY - BASELINE + (font.ascender() + font.descender()) * 0.5F;
        int x = Math.round((screenWidth - width) * 0.5F);
        int yOffset = Math.round(y);
        int shadowOffset = Math.max(1, Math.round(font.pixelSize() * 0.05F));
        drawSpaced(graphics, font.font(), sequence, x + shadowOffset, yOffset + shadowOffset, shadowColor, spacing);
        drawSpaced(graphics, font.font(), sequence, x, yOffset, color, spacing);
    }

    /** Subtitle in the Standard Galactic Alphabet, rendered with the vanilla bitmap font. */
    private static void drawGalactic(GuiGraphics graphics, Font vanillaFont, Component text, int screenWidth,
            float maxWidth, float centerY, @Nullable MainLayout main, int color) {
        float mainSize = main != null ? main.size() : REFERENCE_SIZE;
        // Snap to half steps so that the 8px glyphs stay evenly scaled instead of getting blurred
        // unevenly by a fractional factor.
        float scale = Math.max(1.0F, Math.round(mainSize * SGA_SIZE_FACTOR / VANILLA_GLYPH_HEIGHT * 2.0F) / 2.0F);
        int textWidth = vanillaFont.width(text);
        if (textWidth > 0 && textWidth * scale > maxWidth) {
            scale = maxWidth / textWidth;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate((screenWidth - textWidth * scale) * 0.5F, centerY - 4.0F * scale, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(vanillaFont, text, 0, 0, color, true);
        pose.popPose();
    }

    private static void drawVanillaScaled(GuiGraphics graphics, Font font, Component text, float centerX,
            float centerY, float scale, int color) {
        int textWidth = font.width(text);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX - textWidth * scale * 0.5F, centerY - 4.0F * scale, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, true);
        pose.popPose();
    }

    // ------------------------------------------------------------------ text helpers

    private static void drawSpaced(GuiGraphics graphics, Font font, FormattedCharSequence text, int x, int y,
            int color, float spacing) {
        Matrix4f matrix = graphics.pose().last().pose();
        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        float[] cursor = { x };
        text.accept((index, style, codePoint) -> {
            FormattedCharSequence single = single(codePoint, style);
            font.drawInBatch(single, Math.round(cursor[0]), (float) y, color, false, matrix, buffers,
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            cursor[0] += font.width(single) + spacing;
            return true;
        });
    }

    private static float measure(Font font, FormattedCharSequence text, float spacing) {
        float[] total = { 0.0F };
        int[] count = { 0 };
        text.accept((index, style, codePoint) -> {
            total[0] += font.width(single(codePoint, style));
            count[0]++;
            return true;
        });
        return total[0] + spacing * Math.max(0, count[0] - 1);
    }

    private static FormattedCharSequence single(int codePoint, Style style) {
        return FormattedCharSequence.forward(new String(Character.toChars(codePoint)), style);
    }

    private static int count(FormattedCharSequence text) {
        int[] counter = { 0 };
        text.accept((index, style, codePoint) -> {
            counter[0]++;
            return true;
        });
        return counter[0];
    }

    /** Rasterise at an even pixel size so that resizing the window does not spam the font cache. */
    private static int quantise(float size) {
        return Math.max(2, Math.round(size / 2.0F) * 2);
    }

    private static int currentOversample() {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return Mth.clamp((int) Math.round(guiScale), 1, 4);
    }

    private record MainLayout(SystemFont font, FormattedCharSequence text, float size, float width, float spacing,
            float halfHeight) {
    }
}
