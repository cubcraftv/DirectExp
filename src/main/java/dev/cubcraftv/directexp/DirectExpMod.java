package dev.cubcraftv.directexp;

import dev.cubcraftv.directexp.bootstrap.BackendPlan;
import dev.cubcraftv.directexp.bootstrap.BackendPlanner;
import dev.cubcraftv.directexp.bootstrap.BootstrapState;
import dev.cubcraftv.directexp.config.DirectExpConfig;
import dev.cubcraftv.directexp.config.DirectExpConfigStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DirectExpMod implements ModInitializer {
	public static final String MOD_ID = "directexp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static BootstrapState bootstrapState;

	@Override
	public void onInitialize() {
		DirectExpConfigStore store = new DirectExpConfigStore(
			FabricLoader.getInstance().getConfigDir().resolve("directexp.properties")
		);
		DirectExpConfig config = store.load();
		BackendPlan plan = BackendPlanner.plan(config, isWindows());

		bootstrapState = new BootstrapState(config, plan);
		LOGGER.info("Initializing {} bootstrap", MOD_ID);
	}

	public static BootstrapState bootstrapState() {
		return bootstrapState;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}
}
