package cloud.alistair.market;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /balance /pay /baltop /eco */
public final class EconomyCommands implements TabExecutor {
    private final MarketPlugin plugin;

    public EconomyCommands(MarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName()) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "eco" -> eco(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void balance(CommandSender sender, String[] args) {
        OfflinePlayer who;
        if (args.length > 0) {
            who = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (who == null) {
                Msg.err(sender, "Never seen <p>.", Msg.v("p", args[0]));
                return;
            }
        } else if (sender instanceof Player p) {
            who = p;
        } else {
            Msg.err(sender, "Usage: /balance <player>");
            return;
        }
        long cents = plugin.db().balance(who.getUniqueId());
        Msg.ok(sender, "<white><p></white> has <green><m></green>", Msg.v("p", name(who)), Msg.v("m", Money.format(cents)));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) return;
        if (args.length != 2) {
            Msg.err(sender, "Usage: /pay <player> <amount>");
            return;
        }
        OfflinePlayer to = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (to == null) {
            Msg.err(sender, "Never seen <p>.", Msg.v("p", args[0]));
            return;
        }
        if (to.getUniqueId().equals(p.getUniqueId())) {
            Msg.err(sender, "You can't pay yourself.");
            return;
        }
        OptionalLong amount = Money.parse(args[1]);
        if (amount.isEmpty()) {
            Msg.err(sender, "That's not an amount.");
            return;
        }
        long cents = amount.getAsLong();
        if (plugin.db().balance(p.getUniqueId()) < cents) {
            Msg.err(sender, "You don't have <m>.", Msg.v("m", Money.format(cents)));
            return;
        }
        plugin.db().add(p.getUniqueId(), p.getName(), -cents, "pay-out", name(to));
        plugin.db().add(to.getUniqueId(), name(to), cents, "pay-in", p.getName());
        Msg.ok(sender, "Sent <green><m></green> to <white><p></white>.", Msg.v("m", Money.format(cents)), Msg.v("p", name(to)));
        if (to.getPlayer() != null) {
            Msg.ok(to.getPlayer(), "<white><p></white> sent you <green><m></green>.",
                    Msg.v("p", p.getName()), Msg.v("m", Money.format(cents)));
        }
    }

    private void baltop(CommandSender sender) {
        Map<UUID, Map.Entry<String, Long>> balances = plugin.db().allBalances();
        Map<UUID, List<Db.Holding>> holdings = plugin.db().allHoldings();
        Set<String> symbols = new HashSet<>();
        holdings.values().forEach(l -> l.forEach(h -> symbols.add(h.symbol())));
        List<CompletableFuture<?>> fetches = new ArrayList<>();
        for (String s : symbols) fetches.add(plugin.prices().quote(s).exceptionally(e -> null));
        CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new)).thenRun(() -> plugin.sync(() -> {
            Map<UUID, Long> worth = new HashMap<>();
            balances.forEach((id, e) -> worth.put(id, e.getValue()));
            holdings.forEach((id, list) -> {
                long stocks = 0;
                for (Db.Holding h : list) {
                    PriceService.Quote q = plugin.prices().cached(h.symbol());
                    stocks += q == null ? h.costCents() : Money.value(h.micros(), q.priceCents(), RoundingMode.DOWN);
                }
                worth.merge(id, stocks, Long::sum);
            });
            List<Map.Entry<UUID, Long>> top = new ArrayList<>(worth.entrySet());
            top.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
            sender.sendMessage(Msg.mm("<gradient:#b86bff:#ff6bd6><bold>Richest players</bold></gradient> <dark_gray>(cash + stocks)"));
            for (int i = 0; i < Math.min(10, top.size()); i++) {
                UUID id = top.get(i).getKey();
                String n = balances.containsKey(id) ? balances.get(id).getKey() : name(Bukkit.getOfflinePlayer(id));
                sender.sendMessage(Msg.mm("<gray><i>.</gray> <white><p></white> <green><m></green>",
                        Msg.v("i", String.valueOf(i + 1)), Msg.v("p", n), Msg.v("m", Money.format(top.get(i).getValue()))));
            }
        }));
    }

    private void eco(CommandSender sender, String[] args) {
        if (args.length != 3) {
            Msg.err(sender, "Usage: /eco <give|take|set> <player> <amount>");
            return;
        }
        OfflinePlayer who = Bukkit.getOfflinePlayerIfCached(args[1]);
        OptionalLong amount = Money.parse(args[2]);
        if (who == null || amount.isEmpty()) {
            Msg.err(sender, "Unknown player or bad amount.");
            return;
        }
        long cents = amount.getAsLong();
        String n = name(who);
        switch (args[0].toLowerCase()) {
            case "give" -> plugin.db().add(who.getUniqueId(), n, cents, "admin-give", sender.getName());
            case "take" -> plugin.db().add(who.getUniqueId(), n, -Math.min(cents, plugin.db().balance(who.getUniqueId())),
                    "admin-take", sender.getName());
            case "set" -> plugin.db().set(who.getUniqueId(), n, cents);
            default -> {
                Msg.err(sender, "Usage: /eco <give|take|set> <player> <amount>");
                return;
            }
        }
        Msg.ok(sender, "<white><p></white> now has <green><m></green>.",
                Msg.v("p", n), Msg.v("m", Money.format(plugin.db().balance(who.getUniqueId()))));
    }

    static void give(Player p, ItemStack stack) {
        p.getInventory().addItem(stack).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    static String name(OfflinePlayer p) {
        return p.getName() != null ? p.getName() : p.getUniqueId().toString().substring(0, 8);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName()) {
            case "balance", "pay" -> args.length == 1 ? null : List.of();
            case "eco" -> args.length == 1 ? List.of("give", "take", "set") : args.length == 2 ? null : List.of();
            default -> List.of();
        };
    }
}
