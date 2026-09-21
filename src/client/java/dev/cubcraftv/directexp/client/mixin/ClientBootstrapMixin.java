package dev.cubcraftv.directexp.client.mixin;

import dev.cubcraftv.directexp.DirectExpMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientBootstrapMixin {
	@Inject(method = "run", at = @At("HEAD"))
	private void directExp$onClientRun(CallbackInfo ci) {
		if (DirectExpMod.bootstrapState() == null) {
			DirectExpMod.LOGGER.warn("Client startup hook active before DirectExp bootstrap state was initialized");
			return;
		}

		DirectExpMod.LOGGER.info(
			"Client startup hook active; planned backends={}",
			DirectExpMod.bootstrapState().backendPlan().backendsToTry()
		);
	}
}
