package cloud.alistair.market;

import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;

/** When someone gets in bed but not enough people are asleep yet, says how many more are needed. */
final class SleepNotice implements Listener {
    private static final String PREFIX = "<dark_gray>[<gradient:#b86bff:#ff6bd6>sleep</gradient>]</dark_gray> <gray>";

    private final SmpCore core;

    SleepNotice(SmpCore core) {
        this.core = core;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player sleeper = e.getPlayer();
        World world = sleeper.getWorld();
        // The player only counts as sleeping from the next tick.
        core.getServer().getScheduler().runTask(core.host(), () -> announce(sleeper, world));
    }

    private void announce(Player sleeper, World world) {
        int players = 0, asleep = 0;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isSleepingIgnored()) continue;
            players++;
            if (p.isSleeping()) asleep++;
        }
        Integer pct = world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        // Same rounding as the game: at least one, and round up.
        int needed = Math.max(1, (int) Math.ceil(players * (pct == null ? 100 : pct) / 100.0));
        if (asleep >= needed || !sleeper.isSleeping()) return;
        int more = needed - asleep;
        world.sendMessage(Msg.mm(PREFIX + "<white><p></white> is sleeping. <white><n></white> more "
                        + (more == 1 ? "player needs" : "players need") + " to sleep to skip the night <dark_gray>(<a>/<t>)",
                Msg.v("p", sleeper.getName()), Msg.v("n", String.valueOf(more)),
                Msg.v("a", String.valueOf(asleep)), Msg.v("t", String.valueOf(needed))));
    }
}
