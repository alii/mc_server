package cloud.alistair.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /stock and /portfolio. Prices are real; the money is not. */
public final class StockCommands implements TabExecutor {
    private static final String USAGE = "Usage: /stock <TICKER> | /stock buy <TICKER> <shares|$amount> | /stock sell <TICKER> <shares|all>";

    private final MarketPlugin plugin;

    public StockCommands(MarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("portfolio")) {
            portfolio(sender, args);
            return true;
        }
        if (args.length == 1) {
            withQuote(sender, args[0], q -> showQuote(sender, q));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("buy") && sender instanceof Player p) {
            withQuote(sender, args[1], q -> buy(p, q, args[2]));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("sell") && sender instanceof Player p) {
            withQuote(sender, args[1], q -> sell(p, q, args[2]));
        } else {
            Msg.err(sender, USAGE);
        }
        return true;
    }

    /** Fetches off-thread, then runs {@code then} back on the main thread. */
    private void withQuote(CommandSender sender, String symbol, Consumer<PriceService.Quote> then) {
        plugin.prices().quote(symbol).whenComplete((q, err) -> plugin.sync(() -> {
            if (err == null) {
                then.accept(q);
                return;
            }
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            if (cause instanceof PriceService.QuoteException qe) {
                Msg.err(sender, "<e>", Msg.v("e", qe.getMessage()));
            } else {
                plugin.getLogger().warning("quote " + symbol + " failed: " + cause);
                Msg.err(sender, "Couldn't reach the stock market. Try again in a bit.");
            }
        }));
    }

    private void showQuote(CommandSender sender, PriceService.Quote q) {
        String change = String.format("%+.2f%%", q.changePercent());
        sender.sendMessage(Msg.mm(
                "<gradient:#b86bff:#ff6bd6><bold><s></bold></gradient> <gray><n></gray>\n"
                        + "  <white><price></white> <change>"
                        + " <dark_gray>·</dark_gray> <state>",
                Msg.v("s", q.symbol()), Msg.v("n", q.name()), Msg.v("price", Money.format(q.priceCents())),
                Placeholder.component("change", Msg.mm(q.changePercent() >= 0 ? "<green><v>" : "<red><v>", Msg.v("v", change))),
                Placeholder.component("state", Msg.mm(q.marketOpen() ? "<green>market open" : "<yellow>market closed"))));
    }

    private boolean tradingAllowed(Player p, PriceService.Quote q) {
        if (plugin.settings().marketHoursOnly() && !q.marketOpen()) {
            Msg.err(p, "The market is closed. Trading opens 9:30am New York time on weekdays.");
            return false;
        }
        return true;
    }

    private void buy(Player p, PriceService.Quote q, String amountArg) {
        if (!tradingAllowed(p, q)) return;
        long micros;
        if (amountArg.startsWith("$")) {
            OptionalLong dollars = Money.parse(amountArg);
            if (dollars.isEmpty()) {
                Msg.err(p, "That's not an amount.");
                return;
            }
            // Spend at most that much, fee included.
            long budget = dollars.getAsLong();
            long beforeFee = BigDecimal.valueOf(budget).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(100 + plugin.settings().tradeFeePercent()), 0, RoundingMode.DOWN).longValueExact();
            micros = Money.sharesFor(beforeFee, q.priceCents());
        } else {
            OptionalLong shares = Money.parseShares(amountArg);
            if (shares.isEmpty()) {
                Msg.err(p, "That's not a number of shares.");
                return;
            }
            micros = shares.getAsLong();
        }
        if (micros < 1000) {
            Msg.err(p, "That's too small. Buy at least 0.001 shares.");
            return;
        }
        long cost = Money.value(micros, q.priceCents(), RoundingMode.UP);
        long fee = Money.percent(cost, plugin.settings().tradeFeePercent(), RoundingMode.UP);
        long total = cost + fee;
        long balance = plugin.db().balance(p.getUniqueId());
        if (balance < total) {
            Msg.err(p, "That costs <t> (fee included). You have <b>.", Msg.v("t", Money.format(total)), Msg.v("b", Money.format(balance)));
            return;
        }
        Db.Holding h = plugin.db().holding(p.getUniqueId(), q.symbol()).orElse(new Db.Holding(q.symbol(), 0, 0));
        plugin.db().add(p.getUniqueId(), p.getName(), -total, "stock-buy", Money.shares(micros) + " " + q.symbol());
        plugin.db().putHolding(p.getUniqueId(), q.symbol(), h.micros() + micros, h.costCents() + total);
        Msg.ok(p, "Bought <white><n> <s></white> at <white><price></white> for <green><t></green> <dark_gray>(fee <f>)",
                Msg.v("n", Money.shares(micros)), Msg.v("s", q.symbol()), Msg.v("price", Money.format(q.priceCents())),
                Msg.v("t", Money.format(total)), Msg.v("f", Money.format(fee)));
    }

    private void sell(Player p, PriceService.Quote q, String amountArg) {
        if (!tradingAllowed(p, q)) return;
        var held = plugin.db().holding(p.getUniqueId(), q.symbol());
        if (held.isEmpty()) {
            Msg.err(p, "You don't own any <s>.", Msg.v("s", q.symbol()));
            return;
        }
        Db.Holding h = held.get();
        long micros;
        if (amountArg.equalsIgnoreCase("all")) {
            micros = h.micros();
        } else {
            OptionalLong shares = Money.parseShares(amountArg);
            if (shares.isEmpty()) {
                Msg.err(p, "That's not a number of shares.");
                return;
            }
            micros = shares.getAsLong();
        }
        if (micros > h.micros()) {
            Msg.err(p, "You only have <n> <s>.", Msg.v("n", Money.shares(h.micros())), Msg.v("s", q.symbol()));
            return;
        }
        long gross = Money.value(micros, q.priceCents(), RoundingMode.DOWN);
        long fee = Money.percent(gross, plugin.settings().tradeFeePercent(), RoundingMode.UP);
        long proceeds = Math.max(0, gross - fee);
        long remaining = h.micros() - micros;
        long costLeft = remaining == 0 ? 0 : BigDecimal.valueOf(h.costCents()).multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(h.micros()), 0, RoundingMode.HALF_UP).longValueExact();
        long costSold = h.costCents() - costLeft;
        plugin.db().putHolding(p.getUniqueId(), q.symbol(), remaining, costLeft);
        plugin.db().add(p.getUniqueId(), p.getName(), proceeds, "stock-sell", Money.shares(micros) + " " + q.symbol());
        long pnl = proceeds - costSold;
        Msg.ok(p, "Sold <white><n> <s></white> at <white><price></white> for <green><t></green> <dark_gray>(fee <f>)</dark_gray> <pl>",
                Msg.v("n", Money.shares(micros)), Msg.v("s", q.symbol()), Msg.v("price", Money.format(q.priceCents())),
                Msg.v("t", Money.format(proceeds)), Msg.v("f", Money.format(fee)),
                Placeholder.component("pl", pnl(pnl)));
    }

    private void portfolio(CommandSender sender, String[] args) {
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
            Msg.err(sender, "Usage: /portfolio <player>");
            return;
        }
        List<Db.Holding> holdings = plugin.db().holdings(who.getUniqueId());
        long cash = plugin.db().balance(who.getUniqueId());
        String name = EconomyCommands.name(who);
        if (holdings.isEmpty()) {
            Msg.ok(sender, "<white><p></white> owns no stocks. Cash: <green><c></green>", Msg.v("p", name), Msg.v("c", Money.format(cash)));
            return;
        }
        List<CompletableFuture<?>> fetches = new ArrayList<>();
        for (Db.Holding h : holdings) fetches.add(plugin.prices().quote(h.symbol()).exceptionally(e -> null));
        CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new)).thenRun(() -> plugin.sync(() -> {
            sender.sendMessage(Msg.mm("<gradient:#b86bff:#ff6bd6><bold><p>'s portfolio</bold></gradient>", Msg.v("p", name)));
            long total = 0;
            for (Db.Holding h : holdings) {
                PriceService.Quote q = plugin.prices().cached(h.symbol());
                long value = q == null ? h.costCents() : Money.value(h.micros(), q.priceCents(), RoundingMode.DOWN);
                total += value;
                sender.sendMessage(Msg.mm("  <white><s></white> <gray>× <n></gray> <green><v></green> <pl>",
                        Msg.v("s", h.symbol()), Msg.v("n", Money.shares(h.micros())), Msg.v("v", Money.format(value)),
                        Placeholder.component("pl", pnl(value - h.costCents()))));
            }
            sender.sendMessage(Msg.mm("  <gray>Stocks <green><s></green> · Cash <green><c></green> · Total <green><t></green>",
                    Msg.v("s", Money.format(total)), Msg.v("c", Money.format(cash)), Msg.v("t", Money.format(total + cash))));
        }));
    }

    private static Component pnl(long cents) {
        String s = (cents >= 0 ? "+" : "-") + Money.format(Math.abs(cents));
        return Msg.mm(cents >= 0 ? "<green><v>" : "<red><v>", Msg.v("v", s));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("portfolio")) return args.length == 1 ? null : List.of();
        if (args.length == 1) return List.of("buy", "sell", "AAPL", "MSFT", "NVDA", "SPY");
        if (args.length == 2 && args[0].equalsIgnoreCase("sell") && sender instanceof Player p) {
            return plugin.db().holdings(p.getUniqueId()).stream().map(Db.Holding::symbol).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sell")) return List.of("all");
        return List.of();
    }
}
