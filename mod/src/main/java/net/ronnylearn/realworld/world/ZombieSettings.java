package net.ronnylearn.realworld.world;

/**
 * The mod's own zombies for apocalypse worlds: how many, how dangerous, and the small
 * rules around them. Stored in the world's meta.json and applied by {@link ZombieDirector}.
 */
public final class ZombieSettings {
	public enum Difficulty {
		/** speed, health, damage, follow range, crowd factor, armour chance */
		OFF(0, 0, 0, 0, 0, 0),
		EASY(0.21, 16, 2.0, 24, 0.6, 0.0),
		NORMAL(0.23, 20, 3.0, 35, 1.0, 0.15),
		HARD(0.27, 26, 4.5, 48, 1.5, 0.35),
		NIGHTMARE(0.32, 34, 6.0, 64, 2.2, 0.6);

		public final double speed, health, damage, follow, crowd, armour;

		Difficulty(double speed, double health, double damage, double follow, double crowd, double armour) {
			this.speed = speed;
			this.health = health;
			this.damage = damage;
			this.follow = follow;
			this.crowd = crowd;
			this.armour = armour;
		}
	}

	public enum Density {
		LOW(6), MEDIUM(12), HIGH(20);

		/** Zombies kept around each player at the NORMAL difficulty. */
		public final int perPlayer;

		Density(int perPlayer) {
			this.perPlayer = perPlayer;
		}
	}

	public Difficulty difficulty = Difficulty.OFF;
	public Density density = Density.MEDIUM;
	/** Zombies do not catch fire in daylight. */
	public boolean noBurn = true;
	/** Twice as many at night. */
	public boolean nightHordes = true;
	/** Some zombies come with armour (more on harder difficulties). */
	public boolean armoured = true;
	/** Some zombies are fast runners. */
	public boolean runners = false;
	/** Zombies can break wooden doors. */
	public boolean breakDoors = false;

	public static ZombieSettings preset(Difficulty difficulty) {
		ZombieSettings z = new ZombieSettings();
		z.difficulty = difficulty;
		z.density = difficulty == Difficulty.HARD || difficulty == Difficulty.NIGHTMARE ? Density.HIGH : Density.MEDIUM;
		z.runners = difficulty == Difficulty.HARD || difficulty == Difficulty.NIGHTMARE;
		z.breakDoors = difficulty == Difficulty.NIGHTMARE;
		return z;
	}

	public boolean enabled() {
		return difficulty != Difficulty.OFF;
	}

	public ZombieSettings copy() {
		ZombieSettings z = new ZombieSettings();
		z.difficulty = difficulty;
		z.density = density;
		z.noBurn = noBurn;
		z.nightHordes = nightHordes;
		z.armoured = armoured;
		z.runners = runners;
		z.breakDoors = breakDoors;
		return z;
	}
}
