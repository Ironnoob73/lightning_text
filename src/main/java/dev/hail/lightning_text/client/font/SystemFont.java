package dev.hail.lightning_text.client.font;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * One fully baked "{@code size} pixels tall" instance of a system font.
 *
 * <p>The glyphs are rendered by FreeType at (roughly) their final pixel size and the texture is
 * sampled with nearest neighbour filtering, so the banner text stays crisp instead of being an
 * upscaled 8x8 bitmap font.
 */
public final class SystemFont {
    private final net.minecraft.client.gui.Font font;
    private final int pixelSize;
    private final int oversample;
    private final float ascender;
    private final float descender;
    private final GlyphProvider provider;
    private final ResourceLocation textureId;

    SystemFont(net.minecraft.client.gui.Font font, int pixelSize, int oversample, float ascender,
            float descender, GlyphProvider provider, ResourceLocation textureId) {
        this.font = font;
        this.pixelSize = pixelSize;
        this.oversample = oversample;
        this.ascender = ascender;
        this.descender = descender;
        this.provider = provider;
        this.textureId = textureId;
    }

    public net.minecraft.client.gui.Font font() {
        return this.font;
    }

    /** Nominal pixel height the glyphs were rasterised at. */
    public int pixelSize() {
        return this.pixelSize;
    }

    public int oversample() {
        return this.oversample;
    }

    /** Distance from the baseline to the top of the em box, in pixels. */
    public float ascender() {
        return this.ascender;
    }

    /** Distance from the baseline down to the bottom of the em box, in pixels (negative). */
    public float descender() {
        return this.descender;
    }

    /** Drops the glyph atlas pages and frees the FreeType face. Must run on the render thread. */
    void release() {
        try {
            TextureManager textureManager = net.minecraft.client.Minecraft.getInstance().getTextureManager();
            if (textureManager != null) {
                for (int page = 0; page < 64; page++) {
                    ResourceLocation location = this.textureId.withSuffix("/" + page);
                    if (textureManager.getTexture(location, null) == null) {
                        break;
                    }
                    textureManager.release(location);
                }
            }
        } catch (RuntimeException ignored) {
            // Shutting down / textures already gone: nothing sensible we can do here.
        }
        this.provider.close();
    }
}
