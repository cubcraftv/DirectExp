package dev.cubcraftv.directexp.client.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bootstrap.BackendNegotiationResolver;
import dev.cubcraftv.directexp.bootstrap.BootstrapState;
import dev.cubcraftv.directexp.client.backend.DirectExpBackendFactory;
import dev.cubcraftv.directexp.config.BackendKind;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(net.minecraft.client.Minecraft.class)
public abstract class MinecraftBackendNegotiationMixin {
	@Redirect(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/renderpearl/api/device/GpuBackend;"
		)
	)
	private GpuBackend[] directExp$overrideBackendTryOrder(PreferredGraphicsApi preferredGraphicsApi) {
		BootstrapState bootstrapState = DirectExpMod.bootstrapState();
		if (bootstrapState == null || bootstrapState.backendPlan() == null) {
			return preferredGraphicsApi.getBackendsToTry();
		}

		List<BackendKind> resolvedKinds = BackendNegotiationResolver.resolveBackendTryOrder(bootstrapState.backendPlan());
		GpuBackend[] resolvedBackends = DirectExpBackendFactory.createBackends(resolvedKinds);

		if (resolvedBackends.length == 0) {
			return preferredGraphicsApi.getBackendsToTry();
		}

		DirectExpMod.LOGGER.info("DirectExp startup negotiation order override active: {}", resolvedKinds);
		return resolvedBackends;
	}
}
