package net.ronnylearn.realworld;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.ronnylearn.realworld.mixin.ConfirmScreenAccessor;
import net.ronnylearn.realworld.dev.SelfTest;
import net.ronnylearn.realworld.screen.TitleButton;
import net.ronnylearn.realworld.world.CinematicEntry;
import net.ronnylearn.realworld.world.EntryPlan;
import net.ronnylearn.realworld.world.ZombieDirector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Real World: a "Real World" button in the main menu that opens a library of worlds made
 * from real places, a map to pick a new one, and Arnis to build it inside the game.
 */
public class RealWorldMod implements ClientModInitializer {
	public static final String MOD_ID = "realworld";
	public static final Logger LOGGER = LoggerFactory.getLogger("Real World");

	/** The mod's own folder in the game directory: config, tile cache, the unpacked Arnis library. */
	public static Path dataDir() {
		return FabricLoader.getInstance().getGameDir().resolve("realworld");
	}

	@Override
	public void onInitializeClient() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (CinematicEntry.coversLoadingScreens() && !screen.getClass().getName().startsWith("net.ronnylearn.")) {
				// The game's loading and upgrade screens sit under the mod's dark title card.
				ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> CinematicEntry.drawOverlay(graphics));
			}
			if (screen instanceof TitleScreen) {
				TitleButton.add(screen);
			} else if (screen instanceof ConfirmScreen confirm && EntryPlan.opening()
				&& screen.getTitle().getContents() instanceof TranslatableContents t && t.getKey().equals("upgradeWorld.done")) {
				// The game just moved a generated world to the 26.x layout; join without asking.
				client.execute(() -> ((ConfirmScreenAccessor) confirm).realworld$callback().accept(true));
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(ZombieDirector::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(ZombieDirector::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(ZombieDirector::tick);
		ServerTickEvents.END_SERVER_TICK.register(net.ronnylearn.realworld.world.ChunkPreloader::tick);
		ServerTickEvents.END_SERVER_TICK.register(net.ronnylearn.realworld.world.EntryPlan::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> net.ronnylearn.realworld.world.ChunkPreloader.reset());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> EntryPlan.onJoin(handler.player, server));
		ClientTickEvents.END_CLIENT_TICK.register(CinematicEntry::tick);
		ClientTickEvents.END_CLIENT_TICK.register(net.ronnylearn.realworld.arnis.GenerationTask::notifyWhenDone);
		SelfTest.init();
		net.ronnylearn.realworld.arnis.SpeedModel.benchmarkAsync();
	}
}
