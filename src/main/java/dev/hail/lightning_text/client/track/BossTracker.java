package dev.hail.lightning_text.client.track;

import dev.hail.lightning_text.LightningText;
import dev.hail.lightning_text.client.config.LTConfig;
import dev.hail.lightning_text.client.store.VisitedStore;
import dev.hail.lightning_text.client.title.TitleManager;
import dev.hail.lightning_text.client.title.TitleTexts;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * Shows a banner the first time the player <em>sees</em> a boss type mob in a world / server.
 *
 * <p>"Seen" means the mob is inside the view frustum (so it is actually on screen) and its line of
 * sight from the camera is not blocked by a block. Nothing has to be attacked, and a boss hidden
 * behind the player or behind a wall does not count.
 *
 * <p>"Boss type" means the vanilla bosses (ender dragon, wither), anything in the
 * {@code c:bosses} entity type tag (so mods and data packs can extend the list), plus the warden,
 * the elder guardian and the chicken jockey. The chicken jockey is not a real entity type, so
 * {@code minecraft:chicken_jockey} is used as its pseudo registry name.
 */
public final class BossTracker {
    public static final ResourceLocation CHICKEN_JOCKEY = ResourceLocation.withDefaultNamespace("chicken_jockey");
    /** How far away a boss is even looked for. Entities further out are not synced/rendered anyway. */
    private static final double SEARCH_RADIUS = 96.0D;
    /** Visibility is only re-checked every few ticks, it does not need to be per tick. */
    private static final int SCAN_INTERVAL = 4;

    private static int scanCooldown;

    private BossTracker() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!LTConfig.bossTitleEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        if (++scanCooldown < SCAN_INTERVAL) {
            return;
        }
        scanCooldown = 0;

        Frustum frustum = minecraft.levelRenderer.getFrustum();
        if (frustum == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        AABB area = player.getBoundingBox().inflate(SEARCH_RADIUS);

        for (Entity entity : level.getEntities(player, area, BossTracker::isBossCandidate)) {
            if (entity.isInvisible() || !isVisible(level, minecraft, frustum, cameraPos, entity)) {
                continue;
            }
            ResourceLocation id;
            Component displayName;
            if (isChickenJockey(entity)) {
                id = CHICKEN_JOCKEY;
                displayName = Component.translatable(LightningText.MOD_ID + ".boss.chicken_jockey");
            } else if (entity instanceof LivingEntity living && isBossType(living.getType())) {
                id = EntityType.getKey(living.getType());
                displayName = living.getDisplayName();
            } else {
                continue;
            }
            if (id == null || !VisitedStore.markBoss(id)) {
                continue;
            }
            LightningText.LOGGER.debug("Boss banner for {} (visible from {})", id, cameraPos);
            TitleManager.showBoss(displayName, TitleTexts.galactic(id.toString()));
            return;
        }
    }

    /** Cheap structural check used as the entity query filter; the expensive checks come later. */
    private static boolean isBossCandidate(Entity entity) {
        if (entity instanceof PartEntity<?>) {
            // Multi part bosses (the ender dragon) are announced through the parent entity.
            return false;
        }
        if (isChickenJockey(entity)) {
            return true;
        }
        return entity instanceof LivingEntity living && isBossType(living.getType());
    }

    /** {@code true} when the entity is on screen and not hidden behind blocks. */
    private static boolean isVisible(ClientLevel level, Minecraft minecraft, Frustum frustum, Vec3 cameraPos,
            Entity entity) {
        if (!minecraft.getEntityRenderDispatcher().shouldRender(entity, frustum, cameraPos.x, cameraPos.y, cameraPos.z)) {
            return false;
        }
        AABB box = entity.getBoundingBox();
        return hasLineOfSight(level, entity, cameraPos, box.getCenter())
                || hasLineOfSight(level, entity, cameraPos, entity.getEyePosition());
    }

    private static boolean hasLineOfSight(ClientLevel level, Entity entity, Vec3 from, Vec3 to) {
        ClipContext context = new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity);
        HitResult hit = level.clip(context);
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isBossType(EntityType<?> type) {
        if (type == EntityType.ENDER_DRAGON
                || type == EntityType.WITHER
                || type == EntityType.WARDEN
                || type == EntityType.ELDER_GUARDIAN) {
            return true;
        }
        try {
            return type.is(Tags.EntityTypes.BOSSES);
        } catch (RuntimeException exception) {
            // Tag not synced yet (for example right after joining): treat as "not a boss".
            return false;
        }
    }

    /** A chicken jockey is a zombie riding a chicken; both directions are handled. */
    private static boolean isChickenJockey(@Nullable Entity entity) {
        if (entity instanceof Zombie && entity.getVehicle() instanceof Chicken) {
            return true;
        }
        return entity instanceof Chicken && entity.getFirstPassenger() instanceof Zombie;
    }
}
