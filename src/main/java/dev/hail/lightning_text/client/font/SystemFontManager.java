package dev.hail.lightning_text.client.font;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import dev.hail.lightning_text.LightningText;
import dev.hail.lightning_text.client.config.LTConfig;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_Size;
import org.lwjgl.util.freetype.FT_Size_Metrics;
import org.lwjgl.util.freetype.FreeType;

/**
 * Loads a font file from the operating system and turns it into a Minecraft {@link Font}.
 *
 * <p>Only the vanilla code path is used: the bytes are handed to FreeType through
 * {@link TrueTypeGlyphProvider} and stitched into a private {@link FontSet}, exactly like a
 * resource pack TTF font would be. That keeps the mod portable and free of third party libraries -
 * everything comes from Minecraft / NeoForge (which ships LWJGL FreeType).
 *
 * <p>Baked instances are cached per (size, oversample) because glyph atlases are created lazily on
 * first use; sizes therefore only change when the window is resized or the text length changes.
 */
@OnlyIn(Dist.CLIENT)
public final class SystemFontManager {
    private static final int CACHE_LIMIT = 8;
    /** Glyph textures are single atlas pages of 256x256, keep a generous margin. */
    private static final int MAX_GLYPH_PIXELS = 200;

    private static final Map<Long, SystemFont> CACHE = new LinkedHashMap<>(8, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, SystemFont> eldest) {
            if (this.size() > CACHE_LIMIT) {
                eldest.getValue().release();
                return true;
            }
            return false;
        }
    };

    @Nullable
    private static byte[] fontBytes;
    private static boolean unavailable;
    @Nullable
    private static String loadedFrom;
    @Nullable
    private static String lastConfiguredPath;
    private static int sequence;

    private SystemFontManager() {
    }

    /**
     * Reads the font file once during client startup so that the first banner does not have to wait
     * for a ~10 MiB disk read. Nothing graphical happens here, so this is safe before the game
     * window is created.
     */
    public static void preload() {
        fontBytes();
        lastConfiguredPath = LTConfig.fontPath();
    }

    /**
     * @return a font baked at (or very close to) {@code size} pixels, or {@code null} when no usable
     *         system font could be found. Callers are expected to fall back to the vanilla font.
     */
    @Nullable
    public static SystemFont get(int size, int oversample) {
        if (!Objects.equals(LTConfig.fontPath(), lastConfiguredPath)) {
            lastConfiguredPath = LTConfig.fontPath();
            reset();
        }
        if (unavailable) {
            return null;
        }
        int effectiveOversample = Math.clamp(oversample, 1, 4);
        while (effectiveOversample > 1 && size * effectiveOversample > MAX_GLYPH_PIXELS) {
            effectiveOversample--;
        }
        long key = ((long) size << 8) | effectiveOversample;
        SystemFont cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        SystemFont created = create(size, effectiveOversample);
        if (created != null) {
            CACHE.put(key, created);
        }
        return created;
    }

    /** Drops every cached font and forgets the loaded file (used when the configured path changes). */
    public static void reset() {
        for (SystemFont font : CACHE.values()) {
            font.release();
        }
        CACHE.clear();
        fontBytes = null;
        loadedFrom = null;
        unavailable = false;
    }

    @Nullable
    private static SystemFont create(int size, int oversample) {
        byte[] data = fontBytes();
        if (data == null) {
            return null;
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        FT_Face face = null;
        TrueTypeGlyphProvider trueType = null;
        GlyphProvider provider = null;
        try {
            buffer.put(data).flip();
            long library = FreeTypeUtil.getLibrary();
            float ascender;
            float descender;
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pointer = stack.mallocPointer(1);
                    FreeTypeUtil.assertError(FreeType.FT_New_Memory_Face(library, buffer, 0L, pointer),
                            "Initialising font face");
                    face = FT_Face.create(pointer.get(0));
                }
                String format = FreeType.FT_Get_Font_Format(face);
                if (!"TrueType".equals(format)) {
                    throw new IOException("Font is not in TTF format, was " + format);
                }
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE),
                        "Find unicode charmap");
                // The provider takes ownership of the buffer and the face and applies the pixel size,
                // which is why the metrics have to be read afterwards but before dropping the
                // reference to the face.
                trueType = new TrueTypeGlyphProvider(buffer, face, size, oversample, 0.0F, 0.0F, "");
                FT_Size ftSize = face.size();
                FT_Size_Metrics metrics = ftSize != null ? ftSize.metrics() : null;
                ascender = metrics != null ? metrics.ascender() / 64.0F : size * 0.86F;
                descender = metrics != null ? metrics.descender() / 64.0F : -size * 0.14F;
                face = null; // ownership transferred to the provider
            }

            IntSet supported = new IntArraySet();
            for (int candidate : new int[] { 'A', 'a', '0', ' ', '\u4e2d' }) {
                if (trueType.getGlyph(candidate) != null) {
                    supported.add(candidate);
                    break;
                }
            }
            if (supported.isEmpty()) {
                throw new IOException("Font does not contain a single usable glyph");
            }
            provider = new LimitedGlyphProvider(trueType, supported);

            Minecraft minecraft = Minecraft.getInstance();
            TextureManager textureManager = minecraft.getTextureManager();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(LightningText.MOD_ID,
                    "system_font_" + size + "_" + oversample + "_" + (sequence++));
            FontSet fontSet = new FontSet(textureManager, id);
            fontSet.reload(List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());

            // filterFishyGlyphs must stay false: it rejects glyphs whose advance is wider than 32
            // pixels, which is every single CJK glyph of a title sized font.
            Font font = new Font(location -> fontSet, false);
            return new SystemFont(font, size, oversample, ascender, descender, provider, id);
        } catch (Throwable throwable) {
            LightningText.LOGGER.error("Unable to bake the system font at {}px (file: {})", size, loadedFrom, throwable);
            if (provider != null) {
                provider.close();
            } else if (trueType != null) {
                trueType.close();
            } else {
                if (face != null) {
                    synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                        FreeTypeUtil.checkError(FreeType.FT_Done_Face(face), "Deleting face");
                    }
                }
                MemoryUtil.memFree(buffer);
            }
            unavailable = true;
            return null;
        }
    }

    @Nullable
    private static byte[] fontBytes() {
        if (fontBytes != null) {
            return fontBytes;
        }
        if (unavailable) {
            return null;
        }
        Path path = resolvePath();
        if (path == null) {
            LightningText.LOGGER.warn("No TrueType system font found - the big titles fall back to the vanilla font. "
                    + "Set 'fontPath' in the client config to point at e.g. C:/Windows/Fonts/simhei.ttf");
            unavailable = true;
            return null;
        }
        try {
            long start = System.nanoTime();
            fontBytes = Files.readAllBytes(path);
            loadedFrom = path.toString();
            LightningText.LOGGER.info("Loaded system font {} ({} KiB) in {} ms", loadedFrom, fontBytes.length / 1024,
                    (System.nanoTime() - start) / 1_000_000L);
            return fontBytes;
        } catch (IOException exception) {
            LightningText.LOGGER.error("Failed to read the system font {}", path, exception);
            unavailable = true;
            return null;
        }
    }

    @Nullable
    private static Path resolvePath() {
        String configured = LTConfig.fontPath();
        if (configured != null && !configured.isBlank()) {
            Path path = toPath(configured.trim());
            if (path != null && Files.isReadable(path)) {
                return path;
            }
            LightningText.LOGGER.warn("Configured font '{}' is not readable, falling back to auto detection", configured);
        }
        for (String candidate : candidates()) {
            Path path = toPath(candidate);
            if (path != null && Files.isReadable(path)) {
                return path;
            }
        }
        return null;
    }

    @Nullable
    private static Path toPath(String value) {
        try {
            return Paths.get(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static List<String> candidates() {
        List<String> candidates = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            windir = System.getenv("SystemRoot");
        }
        if (windir != null && !windir.isBlank()) {
            candidates.add(windir + "/Fonts/simhei.ttf"); // SimHei (黑体), the requested default
            candidates.add(windir + "/Fonts/Deng.ttf"); // 等线
            candidates.add(windir + "/Fonts/msyh.ttc"); // Microsoft YaHei
            candidates.add(windir + "/Fonts/simsun.ttc"); // SimSun
        }
        candidates.add("C:/Windows/Fonts/simhei.ttf");
        // Common CJK capable fonts on other platforms.
        candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        candidates.add("/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc");
        candidates.add("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc");
        candidates.add("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf");
        candidates.add("/System/Library/Fonts/STHeiti Medium.ttc");
        candidates.add("/System/Library/Fonts/Hiragino Sans GB.ttc");
        return candidates;
    }

    /**
     * Restricts the reported glyph set to a single code point.
     *
     * <p>{@link FontSet#reload} uses {@link GlyphProvider#getSupportedGlyphs()} to work out which
     * providers stay active and to build the "random glyph" table. Reporting the full Unicode range
     * of a CJK font would make it inspect tens of thousands of glyphs, which is a noticeable hitch
     * right when a banner appears; the actual glyph lookup is not limited by this set.
     */
    private static final class LimitedGlyphProvider implements GlyphProvider {
        private final GlyphProvider delegate;
        private final IntSet supported;

        private LimitedGlyphProvider(GlyphProvider delegate, IntSet supported) {
            this.delegate = delegate;
            this.supported = supported;
        }

        @Nullable
        @Override
        public GlyphInfo getGlyph(int character) {
            return this.delegate.getGlyph(character);
        }

        @Override
        public IntSet getSupportedGlyphs() {
            return this.supported;
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }
}
