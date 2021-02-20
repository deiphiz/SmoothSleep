package com.luffbox.smoothsleep;

import com.luffbox.smoothsleep.lib.ConfigHelper;
import com.luffbox.smoothsleep.lib.MiscUtils;
import com.luffbox.smoothsleep.lib.Purgeable;
import com.luffbox.smoothsleep.lib.TickHelper;
import com.luffbox.smoothsleep.tasks.SleepTickTask;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

/**
 * Contains data about a World that SmoothSleep will use later.
 */
public class WorldData implements Purgeable {

	private final SmoothSleep pl;

	private final World w;
	private final ConfigHelper.WorldSettings ws;
	private final Set<Player> finishedSleeping;
	private TickHelper tickHelper;
	private BukkitTask sleepTickTask;
	private double timescale = 0.0, timeTickRemain;
	private int counter = 0;

	public WorldData(SmoothSleep plugin, World world, ConfigHelper.WorldSettings settings) {
		pl = plugin;
		w = world;
		ws = settings;
		finishedSleeping = new HashSet<>();
	}

	public World getWorld() { return w; }

	public Set<Player> getPlayers() {
		Set<Player> players = new HashSet<>();
		for (Player plr : w.getPlayers()) {
			if (!plr.hasMetadata("NPC")) {
				players.add(plr);
			}
		}
		return players;
	}

	public Set<PlayerData> getPlayerData() {
		Set<PlayerData> pds = new HashSet<>();
		for (Player plr : getPlayers()) {
			PlayerData pd = pl.data.getPlayerData(plr);
			if (pd != null) { pds.add(pd); }
		}
		return pds;
	}

	public Set<Player> getSleepers() {
		Set<Player> sleepers = new HashSet<>();
		getPlayers().forEach(plr -> {
			if (plr.isSleeping()) sleepers.add(plr);
		});
		return sleepers;
	}

	public Set<PlayerData> getSleeperData() {
		Set<PlayerData> pds = new HashSet<>();
		for (Player plr : getSleepers()) {
			PlayerData pd = pl.data.getPlayerData(plr);
			if (pd != null) { pds.add(pd); }
		}
		return pds;
	}

	public boolean hasSleepers() {
		for (Player plr : getPlayers()) { if (plr.isSleeping()) { return true; } }
		return false;
	}

	public Set<Player> getWakers() {
		Set<Player> wakers = new HashSet<>();
		getPlayers().forEach(plr -> {
			if (!plr.isSleeping()) {
				PlayerData pd = pl.data.getPlayerData(plr);
				if (pd == null || !pd.isSleepingIgnored()) wakers.add(plr);
			}
		});
		return wakers;
	}

	public double getSleepRatio() {
		double s = getSleepers().size(); // Sleepers count
		double a = getWakers().size() + s; // Wakers + Sleepers count
		double f = 0; // 0 = first player sleeping will scale
		if (getSettings().getBoolean(ConfigHelper.WorldSettingKey.FIRST_SLEEPING_MIN)) {
			f = 1;
		}
		if (a <= 1 && s >= 1) return 1.0; // Only player is sleeping
		if (a < 1 || s < 1) return 0.0; // No one sleeping or online
		return (s - f) / (a - f); // -1 on both = first player sleeping causes min night mult
	}

	public void resetFinishedSleeping() { finishedSleeping.clear(); }
	public boolean hasFinishedSleeping(Player plr) { return finishedSleeping.contains(plr); }
	public void setFinishedSleeping(Player plr) { finishedSleeping.add(plr); }

	public long getTime() { return w.getTime(); }

	public boolean isNight() { return getTime() >= SmoothSleep.SLEEP_TICKS_START && getTime() < SmoothSleep.SLEEP_TICKS_END; }

	public double getTimeRatio() {
		long current = getTime();
		if (current > SmoothSleep.SLEEP_TICKS_END) { return 1.0; }
		if (current < SmoothSleep.SLEEP_TICKS_START) { return 0.0; }
		return MiscUtils.remapValue(true, 0, SmoothSleep.SLEEP_TICKS_DURA, 0.0, 1.0, current - SmoothSleep.SLEEP_TICKS_START);
	}

	public double getTimescale() { return timescale; }

	private void updateTimescale() {
		if (getWakers().size() <= 0) {
			if (ws.getBoolean(ConfigHelper.WorldSettingKey.INSTANT_DAY)) {
				timescale = SmoothSleep.SLEEP_TICKS_END - getTime();
			} else {
				timescale = ws.getDouble(ConfigHelper.WorldSettingKey.ALL_ASLEEP_NIGHT_MULT);
			}
			return;
		}
		double crv = getSettings().getDouble(ConfigHelper.WorldSettingKey.SPEED_CURVE);
		double mns = ws.getDouble(ConfigHelper.WorldSettingKey.MIN_NIGHT_MULT);
		double xns = ws.getDouble(ConfigHelper.WorldSettingKey.MAX_NIGHT_MULT);
		timescale = MiscUtils.remapValue(true, 0.0, 1.0, mns, xns, MiscUtils.calcSpeed(crv, getSleepRatio()));
	}

	public void timestep() {
		long wtime = getTime();
		updateTimescale();

		// Feels like there's a flaw in this, but too brain-dead to work it out
		timeTickRemain += timescale * pl.data.baseTimeSpeed - pl.data.baseTimeSpeed;
		int ticks = (int) timeTickRemain;
		boolean toMorning = wtime + ticks + ((int) pl.data.baseTimeSpeed) >= SmoothSleep.SLEEP_TICKS_END;

		if (toMorning) {
			ticks = (int) (SmoothSleep.SLEEP_TICKS_END - wtime);
			if (getSettings().getBoolean(ConfigHelper.WorldSettingKey.CLEAR_WEATHER)) {
				clearWeather();
			}
		}
		timestepTimers(ticks, toMorning);
		tickHelper.tick(ticks);
		timeTickRemain %= 1;
	}

	public void timestepTimers(int timescale, boolean isMorning) {
		final int ticks = timescale + 1;
		boolean setSleepTicks = counter > 50;
		getPlayers().forEach(plr -> {
			PlayerData pd = pl.data.getPlayerData(plr);
			if (pd != null) {
				if (isMorning) { setFinishedSleeping(plr); }
				pd.updateUI();
				if (isMorning) {
					pd.hideBossBar();
					pd.clearActionBar();
				}
				if (isNight()) {
					if (plr.isSleeping()) {
						pd.tickTimers(ticks);
						if (setSleepTicks) {
							pd.setSleepTicks(0);
						}
					}
				}
			}
		});
		if (setSleepTicks || isMorning) { counter = 0; } else { counter++; }
	}

	public boolean hasAnyWeather() { return w.isThundering() || w.hasStorm(); }

	public void clearWeather() {
		w.setThundering(false);
		w.setStorm(false);
	}

	public ConfigHelper.WorldSettings getSettings() { return ws; }

	private boolean sleepTickRunning() {
		if (sleepTickTask != null && sleepTickTask.isCancelled()) sleepTickTask = null;
		return sleepTickTask != null;
	}

	public void startSleepTick() {
		if (!pl.data.isPluginEnabled()) return;
		if (sleepTickRunning()) return;
		tickHelper = new TickHelper(w, ws, ws.getTickOptions());
		SleepTickTask stt = new SleepTickTask(pl, this);
		sleepTickTask = stt.runTaskTimer(pl, 0L, 0L);
	}

	public void stopSleepTick() {
		if (sleepTickRunning()) { sleepTickTask.cancel(); }
		sleepTickTask = null;
		if (tickHelper != null) tickHelper.reset();
	}

	@Override
	public void purgeData() {
		if (tickHelper != null) {
			tickHelper.reset();
		}
	}
}
