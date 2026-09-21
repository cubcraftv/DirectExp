package dev.cubcraftv.directexp.client.mixin;

import dev.cubcraftv.directexp.client.settings.DirectExpGraphicsApiOptionFactory;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsDisplayOptionsMixin {
	@Redirect(
		method = "addOptions",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;displayOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"
		)
	)
	private static OptionInstance<?>[] directExp$replaceDisplayOptions(Options options) {
		return new OptionInstance[]{
			options.framerateLimit(),
			options.enableVsync(),
			options.inactivityFpsLimit(),
			options.guiScale(),
			options.fullscreen(),
			options.exclusiveFullscreen(),
			options.gamma(),
			DirectExpGraphicsApiOptionFactory.create(options)
		};
	}
}
