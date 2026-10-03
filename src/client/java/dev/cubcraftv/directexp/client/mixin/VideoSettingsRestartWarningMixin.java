package dev.cubcraftv.directexp.client.mixin;

import dev.cubcraftv.directexp.client.settings.DirectExpGraphicsApiSelectionState;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsRestartWarningMixin {
	@Redirect(
		method = {"addTitle", "tick"},
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/Options;isRestartRequiredToApplyVideoSettings()Z"
		)
	)
	private boolean directExp$includeDirectExpGraphicsApiChange(Options options) {
		return options.isRestartRequiredToApplyVideoSettings() || DirectExpGraphicsApiSelectionState.hasPendingRestart();
	}
}
