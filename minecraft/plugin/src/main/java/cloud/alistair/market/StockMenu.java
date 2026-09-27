package cloud.alistair.market;

import static cloud.alistair.market.MarketMenu.button;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * /stock with no typing: a grid of popular stocks, your portfolio, and a trade screen with
 * buy/sell buttons. Search asks for a ticker in chat.
 */
public final class StockMenu implements Listener {
    /** Where the popular stocks go on the home screen: three rows of seven. */
    static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    static final int PORTFOLIO = 45, BALANCE = 49, SEARCH = 53;
    static final int INFO = 4, BUY_10 = 10, BUY_100 = 11, BUY_1000 = 12, SELL_QUARTER = 14, SELL_HALF = 15, SELL_ALL = 16,
            BACK = 18, REFRESH = 26;
    private static final long SEARCH_TIMEOUT_MS = 60_000;

    private enum Screen { HOME, PORTFOLIO, TRADE }

    private static final class View implements InventoryHolder {
        final Screen screen;
        /** The ticker being traded, for TRADE. */
        final String symbol;
        /** Ticker shown in each slot, for HOME and PORTFOLIO. */
        final String[] slots = new String[54];
        Inventory inv;

        View(Screen screen, String symbol) {
            this.screen = screen;
            this.symbol = symbol;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SmpCore plugin;
    private final StockCommands trades;
    /** Players we asked to type a ticker, and when. */
    private final Map<UUID, Long> searching = new ConcurrentHashMap<>();

    public StockMenu(SmpCore plugin, StockCommands trades) {
        this.plugin = plugin;
        this.trades = trades;
    }

    // --- home ---

    /** Opens straight away with cached prices, then fills in fresh ones as they arrive. */
    public void openHome(Player p) {
        View view = new View(Screen.HOME, null);
        view.inv = Bukkit.createInventory(view, 54, Msg.mm("<dark_purple>Stocks"));
        renderHome(p, view);
        p.openInventory(view.inv);
        refreshWhenFetched(p, view, List.copyOf(plugin.settings().popularStocks().keySet()), () -> renderHome(p, view));
    }

    private void renderHome(Player p, View view) {
        view.inv.clear();
        int i = 0;
        for (var e : plugin.settings().popularStocks().entrySet()) {
            if (i >= GRID.length) break;
            view.inv.setItem(GRID[i], stockIcon(p, e.getKey(), e.getValue(), "<aqua>Click to buy or sell"));
            view.slots[GRID[i]] = e.getKey();
            i++;
        }
        view.inv.setItem(PORTFOLIO, button(Material.ENDER_CHEST, "<white>My stocks", "<gray>What you own and how it's doing"));
        view.inv.setItem(BALANCE, balance(p));
        view.inv.setItem(SEARCH, button(Material.OAK_SIGN, "<white>Find another stock",
                "<gray>Type any US ticker in chat", "<gray>like <white>AAPL</white> or <white>VOO"));
    }

    // --- portfolio ---

    private void openPortfolio(Player p) {
        View view = new View(Screen.PORTFOLIO, null);
        view.inv = Bukkit.createInventory(view, 54, Msg.mm("<dark_purple>My stocks"));
        renderPortfolio(p, view);
        p.openInventory(view.inv);
        List<String> symbols = plugin.db().holdings(p.getUniqueId()).stream().map(Db.Holding::symbol).toList();
        refreshWhenFetched(p, view, symbols, () -> renderPortfolio(p, view));
    }

    private void renderPortfolio(Player p, View view) {
        view.inv.clear();
        List<Db.Holding> holdings = plugin.db().holdings(p.getUniqueId());
        long total = 0;
        for (int i = 0; i < holdings.size() && i < 45; i++) {
            Db.Holding h = holdings.get(i);
            PriceService.Quote q = plugin.prices().cached(h.symbol());
            total += q == null ? h.costCents() : Money.value(h.micros(), q.priceCents(), RoundingMode.DOWN);
            view.inv.setItem(i, stockIcon(p, h.symbol(), icon(h.symbol()), "<aqua>Click to buy or sell"));
            view.slots[i] = h.symbol();
        }
        view.inv.setItem(PORTFOLIO, button(Material.ARROW, "<white>← Back"));
        long cash = plugin.db().balance(p.getUniqueId());
        view.inv.setItem(BALANCE, button(Material.EMERALD, "<green>Total: " + Money.format(total + cash),
                "<gray>Stocks: <green>" + Money.format(total), "<gray>Cash: <green>" + Money.format(cash)));
        if (holdings.isEmpty()) {
            view.inv.setItem(22, button(Material.BARRIER, "<gray>You don't own any stocks yet", "<gray>Go back and pick one"));
        }
    }

    // --- trade ---

    /** Looks the ticker up first, so a typo gets an error instead of an empty screen. */
    public void openTrade(Player p, String rawSymbol) {
        trades.withQuote(p, rawSymbol, q -> {
            View view = new View(Screen.TRADE, q.symbol());
            view.inv = Bukkit.createInventory(view, 27, Msg.mm("<dark_purple>Trade <white><s>", Msg.v("s", q.symbol())));
            renderTrade(p, view, q);
            p.openInventory(view.inv);
        });
    }

    private void renderTrade(Player p, View view, PriceService.Quote q) {
        view.inv.clear();
        view.inv.setItem(INFO, stockIcon(p, q.symbol(), icon(q.symbol()), q.marketOpen()
                ? "<green>Market open" : "<yellow>Market closed <dark_gray>(price is from the last close)"));
        double fee = plugin.settings().tradeFeePercent();
        String feeLine = "<dark_gray>" + fee + "% fee included";
        view.inv.setItem(BUY_10, button(Material.GOLD_NUGGET, "<green>Buy $10 worth", feeLine));
        view.inv.setItem(BUY_100, button(Material.GOLD_INGOT, "<green>Buy $100 worth", feeLine));
        view.inv.setItem(BUY_1000, button(Material.GOLD_BLOCK, "<green>Buy $1,000 worth", feeLine));
        var held = plugin.db().holding(p.getUniqueId(), q.symbol());
        if (held.isPresent()) {
            long micros = held.get().micros();
            view.inv.setItem(SELL_QUARTER, sellButton(Material.RED_DYE, "Sell a quarter", micros / 4, q));
            view.inv.setItem(SELL_HALF, sellButton(Material.RED_CONCRETE, "Sell half", micros / 2, q));
            view.inv.setItem(SELL_ALL, sellButton(Material.REDSTONE_BLOCK, "Sell all", micros, q));
        } else {
            ItemStack none = button(Material.GRAY_DYE, "<gray>Nothing to sell", "<dark_gray>You don't own any " + q.symbol());
            for (int s : new int[] {SELL_QUARTER, SELL_HALF, SELL_ALL}) view.inv.setItem(s, none);
        }
        view.inv.setItem(BACK, button(Material.ARROW, "<white>← All stocks"));
        view.inv.setItem(22, balance(p));
        view.inv.setItem(REFRESH, button(Material.CLOCK, "<white>Refresh price"));
    }

    private ItemStack sellButton(Material m, String label, long micros, PriceService.Quote q) {
        long worth = Money.value(micros, q.priceCents(), RoundingMode.DOWN);
        return button(m, "<red>" + label, "<gray>" + Money.shares(micros) + " shares, about <green>" + Money.format(worth),
                "<dark_gray>before the " + plugin.settings().tradeFeePercent() + "% fee");
    }

    // --- clicks ---

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View view)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= view.inv.getSize()) return;
        switch (view.screen) {
            case HOME -> {
                if (slot == PORTFOLIO) openPortfolio(p);
                else if (slot == SEARCH) askForTicker(p);
                else if (view.slots[slot] != null) openTrade(p, view.slots[slot]);
            }
            case PORTFOLIO -> {
                if (slot == PORTFOLIO) openHome(p);
                else if (view.slots[slot] != null) openTrade(p, view.slots[slot]);
            }
            case TRADE -> clickTrade(p, view, slot);
        }
    }

    private void clickTrade(Player p, View view, int slot) {
        if (slot == BACK) {
            openHome(p);
            return;
        }
        String buy = switch (slot) {
            case BUY_10 -> "$10";
            case BUY_100 -> "$100";
            case BUY_1000 -> "$1000";
            default -> null;
        };
        int sellDivisor = switch (slot) {
            case SELL_QUARTER -> 4;
            case SELL_HALF -> 2;
            case SELL_ALL -> 1;
            default -> 0;
        };
        if (buy == null && sellDivisor == 0 && slot != REFRESH) return;
        trades.withQuote(p, view.symbol, q -> {
            if (buy != null) {
                trades.buy(p, q, buy);
            } else if (sellDivisor > 0) {
                plugin.db().holding(p.getUniqueId(), q.symbol()).ifPresentOrElse(
                        h -> trades.sell(p, q, sellDivisor == 1 ? Long.MAX_VALUE : h.micros() / sellDivisor),
                        () -> Msg.err(p, "You don't own any <s>.", Msg.v("s", q.symbol())));
            }
            if (p.getOpenInventory().getTopInventory() == view.inv) renderTrade(p, view, q);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    // --- search ---

    private void askForTicker(Player p) {
        p.closeInventory();
        searching.put(p.getUniqueId(), System.currentTimeMillis());
        Msg.ok(p, "Type a ticker in chat, like <white>AAPL</white>. Or type <white>cancel</white>.");
    }

    /** Runs first so the ticker never shows up in public chat. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Long asked = searching.remove(e.getPlayer().getUniqueId());
        if (asked == null || System.currentTimeMillis() - asked > SEARCH_TIMEOUT_MS) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        plugin.sync(() -> {
            if (text.equalsIgnoreCase("cancel")) openHome(p);
            else openTrade(p, text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        searching.remove(e.getPlayer().getUniqueId());
    }

    // --- helpers ---

    /** Fetches prices off-thread, then redraws if the player is still looking at this screen. */
    private void refreshWhenFetched(Player p, View view, List<String> symbols, Runnable redraw) {
        if (symbols.isEmpty()) return;
        List<CompletableFuture<?>> fetches = new ArrayList<>();
        for (String s : symbols) fetches.add(plugin.prices().quote(s).exceptionally(err -> null));
        CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new)).thenRun(() -> plugin.sync(() -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory() == view.inv) redraw.run();
        }));
    }

    private Material icon(String symbol) {
        return plugin.settings().popularStocks().getOrDefault(symbol, Material.PAPER);
    }

    /** A stock as an item: price, today's move, and what you own. Uses the cached price only. */
    private ItemStack stockIcon(Player p, String symbol, Material m, String action) {
        PriceService.Quote q = plugin.prices().cached(symbol);
        List<String> lore = new ArrayList<>();
        if (q == null) {
            lore.add("<dark_gray>Loading price…");
        } else {
            lore.add("<gray>" + MiniMessage.miniMessage().escapeTags(q.name()));
            lore.add("<white>" + Money.format(q.priceCents()) + " "
                    + (q.changePercent() >= 0 ? "<green>▲ " : "<red>▼ ") + String.format("%+.2f%%", q.changePercent())
                    + " <dark_gray>today");
        }
        plugin.db().holding(p.getUniqueId(), symbol).ifPresent(h -> {
            lore.add("");
            if (q == null) {
                lore.add("<gray>You own <white>" + Money.shares(h.micros()) + "</white> shares");
            } else {
                long value = Money.value(h.micros(), q.priceCents(), RoundingMode.DOWN);
                long pnl = value - h.costCents();
                lore.add("<gray>You own <white>" + Money.shares(h.micros()) + "</white> shares, worth <green>" + Money.format(value));
                lore.add((pnl >= 0 ? "<green>+" : "<red>-") + Money.format(Math.abs(pnl)) + " <gray>since you bought");
            }
        });
        lore.add("");
        lore.add(action);
        // Tickers are checked against [A-Z0-9.-] before they get here, so they're safe in MiniMessage.
        return button(m, "<gradient:#b86bff:#ff6bd6><bold>" + symbol + "</bold></gradient>", lore.toArray(String[]::new));
    }

    private ItemStack balance(Player p) {
        return button(Material.EMERALD, "<green>Cash: " + Money.format(plugin.db().balance(p.getUniqueId())));
    }
}
