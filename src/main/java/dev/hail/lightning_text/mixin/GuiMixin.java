package dev.hail.lightning_text.mixin;

import dev.hail.lightning_text.client.title.TitleManager;
import dev.hail.lightning_text.client.title.TitleRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects everything that would end up in the vanilla title/subtitle slot (which is what
 * {@code /title} writes to) into {@link TitleManager}, and replaces the vanilla title rendering with
 * {@link TitleRenderer}.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void lightning_text$setTitle(Component title, CallbackInfo callback) {
        TitleManager.onCommandTitle(title);
        callback.cancel();
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void lightning_text$setSubtitle(Component subtitle, CallbackInfo callback) {
        TitleManager.onCommandSubtitle(subtitle);
        callback.cancel();
    }

    @Inject(method = "setTimes", at = @At("HEAD"))
    private void lightning_text$setTimes(int fadeIn, int stay, int fadeOut, CallbackInfo callback) {
        TitleManager.onCommandTimes(fadeIn, stay, fadeOut);
    }

    @Inject(method = "resetTitleTimes", at = @At("HEAD"))
    private void lightning_text$resetTitleTimes(CallbackInfo callback) {
        TitleManager.onCommandResetTimes();
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void lightning_text$clear(CallbackInfo callback) {
        TitleManager.clear();
    }

    @Inject(method = "renderTitle", at = @At("HEAD"), cancellable = true)
    private void lightning_text$renderTitle(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        TitleRenderer.render(graphics, deltaTracker);
        callback.cancel();
    }
}
