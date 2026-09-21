package dev.cubcraftv.directexp.client.settings;

import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bootstrap.BootstrapState;
import dev.cubcraftv.directexp.config.GraphicsApiPreference;
import dev.cubcraftv.directexp.config.DirectExpConfig;
import dev.cubcraftv.directexp.config.DirectExpConfigStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class DirectExpGraphicsApiOptionFactory {
	private static final String OPTION_KEY = "directexp.options.graphicsApi";

	private DirectExpGraphicsApiOptionFactory() {
	}

	public static OptionInstance<GraphicsApiPreference> create(Options options) {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve("directexp.properties");
		BootstrapState bootstrapState = DirectExpMod.bootstrapState();
		DirectExpConfig fallbackConfig = bootstrapState == null ? DirectExpConfig.defaults() : bootstrapState.config();
		DirectExpConfig loadedConfig = new DirectExpConfigStore(configPath).load();
		final DirectExpConfig baselineConfig = loadedConfig == null ? fallbackConfig : loadedConfig;
		final Path finalConfigPath = configPath;

		GraphicsApiPreference initialValue = GraphicsApiPreference.fromBackendKind(baselineConfig.backend());
		DirectExpGraphicsApiSelectionState.onVideoSettingsOpened(initialValue.toBackendKind());

		return new OptionInstance<>(
			OPTION_KEY,
			OptionInstance.cachedConstantTooltip(Component.translatable("directexp.options.graphicsApi.tooltip")),
			(caption, value) -> Options.genericValueLabel(caption, Component.translatable("directexp.options.graphicsApi." + value.id())),
			new OptionInstance.Enum<>(List.of(GraphicsApiPreference.values()), GraphicsApiPreference.CODEC),
			GraphicsApiPreference.CODEC,
			initialValue,
			value -> persistSelection(value, baselineConfig, finalConfigPath)
		);
	}

	private static void persistSelection(GraphicsApiPreference selected, DirectExpConfig baselineConfig, Path configPath) {
		DirectExpConfig updatedConfig = new DirectExpConfig(
			selected.toBackendKind(),
			baselineConfig.failureMode(),
			baselineConfig.diagnosticsEnabled()
		);

		DirectExpGraphicsApiSelectionState.onSelectionApplied(updatedConfig.backend());
		DirectExpConfigStore store = new DirectExpConfigStore(configPath);

		try {
			store.save(updatedConfig);
		} catch (IOException exception) {
			DirectExpMod.LOGGER.warn("Failed to persist DirectExp graphics API selection to {}", configPath, exception);
		}
	}
}
