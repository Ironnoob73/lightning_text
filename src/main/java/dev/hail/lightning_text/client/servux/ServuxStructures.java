package dev.hail.lightning_text.client.servux;

import dev.hail.lightning_text.LightningText;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Optional integration with <a href="https://github.com/maruohon/servux">Servux</a>.
 *
 * <p>Servux runs on the server and pushes structure bounding boxes over the {@code servux:structures}
 * plugin channel. Vanilla never sends structure data to clients, so this is the only way to know
 * about structures when playing on a remote server. Everything here is protocol level, no Servux
 * classes are referenced, so the mod stays a normal optional client-side integration.
 *
 * <p>The protocol (Servux 0.3.x, protocol version 2):
 * <ul>
 *     <li>{@code varint 1} + NBT metadata ({@code id}, {@code version}, {@code timeout}).</li>
 *     <li>{@code varint 2} + a (possibly split) NBT compound {@code {Structures: [start, ...]}}.
 *     The first fragment starts with a {@code varint} total size; the following fragments are the
 *     remaining bytes.</li>
 *     <li>{@code varint 3} / {@code varint 4} + empty NBT register / unregister request.</li>
 * </ul>
 * Each structure start is the vanilla {@code StructureStart.createTag} layout, so the bounding box
 * is the union of the {@code BB} int arrays of its {@code Children}.
 */
public final class ServuxStructures {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("servux", "structures");

    private static final int TYPE_METADATA = 1;
    private static final int TYPE_STRUCTURE_DATA = 2;
    private static final int TYPE_REGISTER = 3;
    private static final int TYPE_UNREGISTER = 4;
    private static final int PROTOCOL_VERSION = 2;
    /** Servux itself caps a single transfer at 128 MiB; structures are far below that. */
    private static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_TIMEOUT_TICKS = 600;
    private static final int MIN_EXPIRY_TICKS = 1200;

    private static final Map<StructureKey, StructureEntry> STRUCTURES = new HashMap<>();

    @Nullable
    private static ClientPacketListener connection;
    @Nullable
    private static ResourceKey<Level> dimension;
    private static boolean registered;
    private static boolean advertised;
    private static int advertiseDelay;
    private static boolean active;
    private static int serverTimeoutTicks = DEFAULT_TIMEOUT_TICKS;
    private static long tick;

    private static int expectedSize = -1;
    @Nullable
    private static byte[] received;
    private static int receivedBytes;

    private ServuxStructures() {
    }

    // ------------------------------------------------------------------ registration + lifecycle

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playBidirectional(Payload.TYPE, Payload.CODEC, ServuxStructures::handlePayload);
    }

    /**
     * Keeps the Servux registration in sync with the current connection and dimension.
     *
     * @param enabled {@code false} when the feature is disabled or when the integrated server is used
     *                (singleplayer structure data comes straight from the integrated server instead).
     */
    public static void tick(Minecraft minecraft, @Nullable ClientLevel level, boolean enabled) {
        if (!enabled) {
            if (registered) {
                send(minecraft, TYPE_UNREGISTER);
            }
            clear();
            return;
        }
        ClientPacketListener currentConnection = minecraft.getConnection();
        if (level == null || currentConnection == null) {
            clear();
            return;
        }
        if (currentConnection != connection) {
            clear();
            connection = currentConnection;
        }
        if (!level.dimension().equals(dimension)) {
            // Structures from the previous dimension are meaningless here. Re-registering makes
            // Servux send a fresh initial sync for the new dimension.
            if (dimension != null) {
                STRUCTURES.clear();
                active = false;
                registered = false;
                resetTransfer();
            }
            dimension = level.dimension();
        }
        tick++;
        if ((tick & 0x3F) == 0) {
            expire();
        }
        // Fabric's play channel registration happens after the configuration phase. NeoForge
        // advertises play channels slightly differently, so send a plain minecraft:register with
        // our channel once the play connection exists; Fabric servers then allow Servux to send.
        if (!advertised) {
            advertised = advertiseChannel();
            advertiseDelay = advertised ? 2 : 0;
        }
        if (advertiseDelay > 0) {
            advertiseDelay--;
        } else if (!registered && NetworkRegistry.hasChannel(currentConnection, CHANNEL)) {
            registered = send(minecraft, TYPE_REGISTER);
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** @return the first known structure whose bounding box contains {@code pos}, or {@code null}. */
    @Nullable
    public static ResourceLocation structureAt(BlockPos pos) {
        for (Map.Entry<StructureKey, StructureEntry> entry : STRUCTURES.entrySet()) {
            if (entry.getValue().box().isInside(pos)) {
                return entry.getKey().id();
            }
        }
        return null;
    }

    private static void clear() {
        STRUCTURES.clear();
        connection = null;
        dimension = null;
        registered = false;
        advertised = false;
        advertiseDelay = 0;
        active = false;
        serverTimeoutTicks = DEFAULT_TIMEOUT_TICKS;
        tick = 0;
        resetTransfer();
    }

    private static void expire() {
        long expiry = Math.max(MIN_EXPIRY_TICKS, serverTimeoutTicks * 2L);
        long cutoff = tick - expiry;
        STRUCTURES.values().removeIf(entry -> entry.updatedTick() < cutoff);
    }

    /**
     * Advertises the channel with the vanilla {@code minecraft:register} mechanism, which Fabric
     * servers understand. Needed on top of NeoForge's own negotiation because Fabric servers only
     * accept play channels that were registered during the play phase.
     */
    private static boolean advertiseChannel() {
        try {
            PacketDistributor.sendToServer(new MinecraftRegisterPayload(Set.of(CHANNEL)));
            return true;
        } catch (RuntimeException exception) {
            LightningText.LOGGER.warn("Failed to advertise the Servux structures channel", exception);
            return false;
        }
    }

    private static boolean send(Minecraft minecraft, int type) {
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null || !NetworkRegistry.hasChannel(listener, CHANNEL)) {
            return false;
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(type);
            buffer.writeNbt(new CompoundTag());
            PacketDistributor.sendToServer(new Payload(ByteBufUtil.getBytes(buffer)));
            resetTransfer();
            return true;
        } catch (RuntimeException exception) {
            LightningText.LOGGER.warn("Failed to send a Servux structures packet (type {})", type, exception);
            return false;
        } finally {
            buffer.release();
        }
    }

    // ------------------------------------------------------------------ receiving

    private static void handlePayload(Payload payload, IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            receive(payload.data());
        }
    }

    private static void receive(byte[] data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int type = buffer.readVarInt();
            switch (type) {
                case TYPE_METADATA -> handleMetadata(buffer.readNbt());
                case TYPE_STRUCTURE_DATA -> handleStructureData(buffer);
                default -> {
                    // Spawn metadata / weather data share this channel but are irrelevant here.
                }
            }
        } catch (RuntimeException exception) {
            LightningText.LOGGER.warn("Failed to read a Servux structures packet", exception);
            resetTransfer();
        } finally {
            buffer.release();
        }
    }

    private static void handleMetadata(@Nullable CompoundTag metadata) {
        if (metadata == null) {
            return;
        }
        String id = metadata.getString("id");
        if (!id.isEmpty() && !CHANNEL.toString().equals(id)) {
            return;
        }
        int version = metadata.getInt("version");
        if (version < PROTOCOL_VERSION) {
            LightningText.LOGGER.warn("Servux structures protocol {} is too old (need {}); structure banners disabled for this server",
                    version, PROTOCOL_VERSION);
            active = false;
            return;
        }
        int timeout = metadata.getInt("timeout");
        serverTimeoutTicks = timeout > 0 ? timeout : DEFAULT_TIMEOUT_TICKS;
        // A new registration always starts a new data transfer.
        resetTransfer();
        active = true;
    }

    private static void handleStructureData(FriendlyByteBuf buffer) {
        if (expectedSize < 0) {
            int size = buffer.readVarInt();
            if (size <= 0 || size > MAX_TRANSFER_BYTES) {
                resetTransfer();
                return;
            }
            expectedSize = size;
            received = new byte[size];
            receivedBytes = 0;
        }
        int available = buffer.readableBytes();
        if (available <= 0) {
            return;
        }
        if (receivedBytes + available > expectedSize) {
            resetTransfer();
            return;
        }
        buffer.readBytes(received, receivedBytes, available);
        receivedBytes += available;
        if (receivedBytes == expectedSize) {
            byte[] complete = received;
            resetTransfer();
            parseStructures(complete);
        }
    }

    private static void parseStructures(byte[] data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            CompoundTag root = buffer.readNbt();
            if (root == null) {
                return;
            }
            ListTag structures = root.getList("Structures", Tag.TAG_COMPOUND);
            for (int i = 0; i < structures.size(); i++) {
                CompoundTag start = structures.getCompound(i);
                String rawId = start.getString("id");
                if (rawId.isEmpty() || "INVALID".equals(rawId)) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(rawId);
                if (id == null) {
                    continue;
                }
                BoundingBox box = unionOfChildren(start.getList("Children", Tag.TAG_COMPOUND));
                if (box == null) {
                    continue;
                }
                StructureKey key = new StructureKey(id, start.getInt("ChunkX"), start.getInt("ChunkZ"));
                STRUCTURES.put(key, new StructureEntry(box, tick));
            }
        } catch (RuntimeException exception) {
            LightningText.LOGGER.warn("Failed to parse Servux structure data", exception);
        } finally {
            buffer.release();
        }
    }

    @Nullable
    private static BoundingBox unionOfChildren(ListTag children) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (int i = 0; i < children.size(); i++) {
            int[] box = children.getCompound(i).getIntArray("BB");
            if (box.length != 6) {
                continue;
            }
            found = true;
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            minZ = Math.min(minZ, box[2]);
            maxX = Math.max(maxX, box[3]);
            maxY = Math.max(maxY, box[4]);
            maxZ = Math.max(maxZ, box[5]);
        }
        return found ? new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    private static void resetTransfer() {
        expectedSize = -1;
        received = null;
        receivedBytes = 0;
    }

    private record StructureKey(ResourceLocation id, int chunkX, int chunkZ) {
    }

    private record StructureEntry(BoundingBox box, long updatedTick) {
    }

    /** Opaque payload carrying the raw Servux structures packet bytes. */
    public record Payload(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Payload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);

        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeBytes(payload.data()),
                buffer -> {
                    byte[] data = new byte[buffer.readableBytes()];
                    buffer.readBytes(data);
                    return new Payload(data);
                });

        @Override
        public CustomPacketPayload.Type<Payload> type() {
            return TYPE;
        }
    }
}
