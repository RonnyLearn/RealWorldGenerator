package net.ronnylearn.realworld.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.ronnylearn.realworld.RealWorldMod;

import java.nio.file.Path;

/**
 * Keeps an apocalypse world populated: around every player a crowd of zombies sized by the
 * world's density and difficulty (twice as big at night with hordes on), spawned on open
 * ground inside the generated area, with the speed, health, damage and gear of the chosen
 * difficulty. Runs on the integrated server.
 */
public final class ZombieDirector {
	public static final String TAG = "realworld_zombie";
	private static final int PERIOD = 40;
	private static final double RANGE = 64;

	private static volatile ZombieSettings settings;
	private static int[] area;

	private ZombieDirector() {
	}

	/** Zombies of the current world do not catch fire in daylight. */
	public static boolean noBurn() {
		ZombieSettings s = settings;
		return s != null && s.noBurn;
	}

	public static void onServerStarted(MinecraftServer server) {
		settings = null;
		area = null;
		Path world = server.getWorldPath(LevelResource.ROOT).normalize();
		WorldMeta meta = WorldMeta.read(world);
		if (meta == null || meta.zombies == null || !meta.zombies.enabled()) {
			return;
		}
		settings = meta.zombies;
		area = meta.area;
		// Peaceful removes every monster; an apocalypse world needs at least "normal".
		if (server.getWorldData().getDifficulty() == Difficulty.PEACEFUL) {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "difficulty normal");
		}
		RealWorldMod.LOGGER.info("Zombies on: {} / {}", settings.difficulty, settings.density);
	}

	public static void onServerStopped(MinecraftServer server) {
		settings = null;
		area = null;
	}

	public static void tick(MinecraftServer server) {
		ZombieSettings s = settings;
		// Nobody is in danger while the world is still being prepared behind the dark card.
		if (s == null || server.getTickCount() % PERIOD != 0 || ChunkPreloader.running()) {
			return;
		}
		ServerLevel level = server.overworld();
		boolean night = level.isDarkOutside();
		int target = (int) Math.round(s.density.perPlayer * s.difficulty.crowd * (night && s.nightHordes ? 2 : 1));
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			int around = level.getEntities(EntityTypeTest.forClass(Zombie.class),
				z -> z.isAlive() && z.entityTags().contains(TAG) && z.distanceToSqr(player) < RANGE * RANGE).size();
			for (int i = 0; i < Math.min(2, target - around); i++) {
				spawnNear(level, player, s);
			}
		}
	}

	private static void spawnNear(ServerLevel level, ServerPlayer player, ZombieSettings s) {
		RandomSource random = level.getRandom();
		for (int attempt = 0; attempt < 6; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			// Out of sight but close enough to come: about thirty blocks away.
			double distance = 27 + random.nextDouble() * 8;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
			int[] a = area;
			if (a != null && (x < a[0] || x > a[2] || z < a[1] || z > a[3])) {
				continue;
			}
			BlockPos column = new BlockPos(x, 0, z);
			if (!level.isLoaded(column)) {
				continue;
			}
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.getFluidState(pos.below()).isEmpty() || !level.getBlockState(pos).isAir()) {
				continue;
			}
			Zombie zombie = EntityTypes.ZOMBIE.spawn(level, pos, EntitySpawnReason.EVENT);
			if (zombie != null) {
				configure(zombie, s, random);
				return;
			}
		}
	}

	private static void configure(Zombie zombie, ZombieSettings s, RandomSource random) {
		ZombieSettings.Difficulty d = s.difficulty;
		zombie.addTag(TAG);
		double speed = d.speed * (s.runners && random.nextFloat() < 0.2f ? 1.4 : 1.0);
		set(zombie.getAttribute(Attributes.MOVEMENT_SPEED), speed);
		set(zombie.getAttribute(Attributes.MAX_HEALTH), d.health);
		set(zombie.getAttribute(Attributes.ATTACK_DAMAGE), d.damage);
		set(zombie.getAttribute(Attributes.FOLLOW_RANGE), d.follow);
		zombie.setHealth((float) d.health);
		zombie.setCanBreakDoors(s.breakDoors);
		if (s.armoured && random.nextDouble() < d.armour) {
			Item[] helmets = {Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET};
			Item[] chests = {Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.IRON_CHESTPLATE};
			int tier = Math.min(2, random.nextInt(3) + (d == ZombieSettings.Difficulty.NIGHTMARE ? 1 : 0));
			zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmets[tier]));
			if (random.nextBoolean()) {
				zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chests[tier]));
			}
		}
	}

	private static void set(AttributeInstance attribute, double value) {
		if (attribute != null) {
			attribute.setBaseValue(value);
		}
	}
}
