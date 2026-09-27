package cloud.alistair.market;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Everyone gets the starting balance once, the first time they join. */
public final class JoinBonus implements Listener {
    private final MarketPlugin plugin;

    public JoinBonus(MarketPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var p = e.getPlayer();
        if (plugin.db().hasAccount(p.getUniqueId())) return;
        long start = plugin.settings().startingCents();
        plugin.db().add(p.getUniqueId(), p.getName(), start, "starting-balance", "");
        if (start > 0) {
            Msg.ok(p, "Welcome! Here's <green><m></green> to start. Sell what you gather with <white>/shop</white>.",
                    Msg.v("m", Money.format(start)));
        }
    }
}
