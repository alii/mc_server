package cloud.alistair.market;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** Admin tools. Just {@code /smp reload} for now. */
public final class SmpCommand implements TabExecutor {
    private final MarketPlugin plugin;

    public SmpCommand(MarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            Msg.err(sender, "Usage: /smp reload");
            return true;
        }
        plugin.reload();
        Msg.ok(sender, "Reloaded config.yml and shop.yml (<n> shop items)",
                Msg.v("n", String.valueOf(plugin.catalog().size())));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? List.of("reload") : List.of();
    }
}
