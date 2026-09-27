package cloud.alistair.market;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The server shop. Selling pays less the more of an item the whole server has sold lately
 * (it recovers over time), so farms can't print money forever. Buying costs a flat multiple.
 */
public final class ShopMenu implements TabExecutor, Listener {
    private static final int BACK = 49;

    /** categoryIndex == -1 is the category picker. */
    private static final class View implements InventoryHolder {
        final int categoryIndex;
        Inventory inv;

        View(int categoryIndex) {
            this.categoryIndex = categoryIndex;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final MarketPlugin plugin;
    private final java.util.Map<java.util.UUID, Long> pendingSellAll = new java.util.HashMap<>();

    public ShopMenu(MarketPlugin plugin) {
        this.plugin = plugin;
    }

    /** Open menus point at categories by index, which a reload can shift. */
    public static void closeAll(org.bukkit.Server server) {
        for (Player p : server.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof View) p.closeInventory();
        }
    }

    // --- pricing ---

    /**
     * Pays the integral of worth × E / (E + pressure) over the amount being sold, so selling 1000
     * at once pays the same as selling 1 at a time.
     */
    private long sellPayout(ShopCatalog.Item it, int count) {
        Settings s = plugin.settings();
        double value = (double) it.worthCents() * count;
        double e = s.saturationCents();
        if (e <= 0) return (long) value;
        double p = plugin.db().pressure(it.base().name(), s.recoveryHalfLifeHours());
        return (long) Math.floor(e * Math.log((e + p + value) / (e + p)));
    }

    private long unitSell(ShopCatalog.Item it) {
        return sellPayout(it, 1);
    }

    private long buyCost(ShopCatalog.Item it, int count) {
        return (long) Math.ceil(it.worthCents() * plugin.settings().buyMultiplier() * count);
    }

    // --- actions ---

    private long sell(Player p, ShopCatalog.Item it, int count) {
        long payout = sellPayout(it, count);
        takeFromInventory(p.getInventory(), it.material(), count);
        plugin.db().addPressure(it.base().name(), (double) it.worthCents() * count, plugin.settings().recoveryHalfLifeHours());
        plugin.db().add(p.getUniqueId(), p.getName(), payout, "shop-sell", count + " " + it.material().name());
        return payout;
    }

    private void buy(Player p, ShopCatalog.Item it, int count) {
        if (!it.buyable()) {
            Msg.err(p, "The shop doesn't sell that. Find it or buy it from a player.");
            return;
        }
        long cost = buyCost(it, count);
        long balance = plugin.db().balance(p.getUniqueId());
        if (balance < cost) {
            Msg.err(p, "That's <m>. You have <b>.", Msg.v("m", Money.format(cost)), Msg.v("b", Money.format(balance)));
            return;
        }
        plugin.db().add(p.getUniqueId(), p.getName(), -cost, "shop-buy", count + " " + it.material().name());
        EconomyCommands.give(p, new ItemStack(it.material(), count));
        Msg.ok(p, "Bought <white><n>× <i></white> for <green><m></green>.",
                Msg.v("n", String.valueOf(count)), Msg.v("i", pretty(it.material())), Msg.v("m", Money.format(cost)));
    }

    private void sellAllOf(Player p, ShopCatalog.Item it) {
        int n = countPlain(p.getInventory(), it.material());
        if (n == 0) {
            Msg.err(p, "You have no <i> to sell.", Msg.v("i", pretty(it.material())));
            return;
        }
        long got = sell(p, it, n);
        Msg.ok(p, "Sold <white><n>× <i></white> for <green><m></green>.",
                Msg.v("n", String.valueOf(n)), Msg.v("i", pretty(it.material())), Msg.v("m", Money.format(got)));
    }

    // --- commands ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        switch (command.getName()) {
            case "shop" -> openCategories(p);
            case "worth" -> worth(p);
            case "sell" -> {
                String what = args.length > 0 ? args[0].toLowerCase() : "hand";
                if (what.equals("all")) sellEverything(p, args.length > 1 && args[1].equalsIgnoreCase("confirm"));
                else if (what.equals("hand")) sellHand(p);
                else Msg.err(p, "Usage: /sell [hand|all]");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void worth(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        ShopCatalog.Item it = hand.getType().isAir() ? null : plugin.catalog().get(hand.getType());
        if (it == null) {
            Msg.err(p, "The shop doesn't buy that.");
            return;
        }
        if (!it.sellable()) {
            Msg.ok(p, "<white><i></white> is buy-only. It costs <yellow><b></yellow> in /shop.",
                    Msg.v("i", pretty(it.material())), Msg.v("b", Money.format(buyCost(it, 1))));
            return;
        }
        Msg.ok(p, "<white><i></white> sells for <green><s></green> each<buy>",
                Msg.v("i", pretty(it.material())), Msg.v("s", Money.format(unitSell(it))),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("buy", it.buyable()
                        ? Msg.mm(" <dark_gray>·</dark_gray> buy for <yellow><b></yellow>", Msg.v("b", Money.format(buyCost(it, 1))))
                        : Msg.mm(" <dark_gray>· not sold by the shop")));
    }

    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        ShopCatalog.Item it = hand.getType().isAir() ? null : plugin.catalog().sellable(hand.getType());
        if (it == null || !hand.isSimilar(new ItemStack(hand.getType()))) {
            Msg.err(p, "The shop doesn't buy that.");
            return;
        }
        int n = hand.getAmount();
        p.getInventory().setItemInMainHand(null);
        long payout = sellPayout(it, n);
        plugin.db().addPressure(it.base().name(), (double) it.worthCents() * n, plugin.settings().recoveryHalfLifeHours());
        plugin.db().add(p.getUniqueId(), p.getName(), payout, "shop-sell", n + " " + it.material().name());
        Msg.ok(p, "Sold <white><n>× <i></white> for <green><m></green>.",
                Msg.v("n", String.valueOf(n)), Msg.v("i", pretty(it.material())), Msg.v("m", Money.format(payout)));
    }

    /** Sells every plain shop item in the main inventory (not armor or offhand). Previews first. */
    private void sellEverything(Player p, boolean confirmed) {
        PlayerInventory inv = p.getInventory();
        java.util.Map<Material, Integer> counts = new java.util.LinkedHashMap<>();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || plugin.catalog().sellable(s.getType()) == null || !s.isSimilar(new ItemStack(s.getType()))) continue;
            counts.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        if (counts.isEmpty()) {
            Msg.err(p, "Nothing in your inventory the shop buys.");
            return;
        }
        Long asked = pendingSellAll.remove(p.getUniqueId());
        if (!confirmed || asked == null || System.currentTimeMillis() - asked > 30_000) {
            previewSellAll(p, counts);
            return;
        }
        long total = 0;
        int items = 0;
        for (var e : counts.entrySet()) {
            total += sell(p, plugin.catalog().get(e.getKey()), e.getValue());
            items += e.getValue();
        }
        Msg.ok(p, "Sold <white><n></white> items for <green><m></green>.",
                Msg.v("n", String.valueOf(items)), Msg.v("m", Money.format(total)));
    }

    private void previewSellAll(Player p, java.util.Map<Material, Integer> counts) {
        pendingSellAll.put(p.getUniqueId(), System.currentTimeMillis());
        long total = 0;
        p.sendMessage(Msg.mm("<gradient:#b86bff:#ff6bd6><bold>/sell all</bold></gradient> <gray>would sell:"));
        for (var e : counts.entrySet()) {
            ShopCatalog.Item it = plugin.catalog().get(e.getKey());
            long got = sellPayout(it, e.getValue());
            total += got;
            p.sendMessage(Msg.mm("  <white><n>× <i></white> <green><m></green><rare>",
                    Msg.v("n", String.valueOf(e.getValue())), Msg.v("i", pretty(e.getKey())), Msg.v("m", Money.format(got)),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("rare",
                            it.buyable() ? Component.empty() : Msg.mm(" <gold>⚠ can't buy back"))));
        }
        p.sendMessage(Msg.mm("  <gray>Total: <green><m></green>  <confirm>  <dark_gray>(30s)",
                Msg.v("m", Money.format(total)),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("confirm",
                        Msg.mm("<green><bold>[Confirm]</bold>")
                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/sell all confirm"))
                                .hoverEvent(Msg.mm("<gray>Click to sell all of this")))));
    }

    // --- menus ---

    private void openCategories(Player p) {
        View view = new View(-1);
        view.inv = Bukkit.createInventory(view, 27, Msg.mm("<dark_purple>Shop"));
        List<ShopCatalog.Category> cats = plugin.catalog().categories();
        int[] slots = {10, 11, 12, 14, 15, 16, 19, 20, 21, 23, 24, 25};
        for (int i = 0; i < cats.size() && i < slots.length; i++) {
            ShopCatalog.Category c = cats.get(i);
            view.inv.setItem(slots[i], button(c.icon(), "<white>" + c.name(), "<gray>" + c.items().size() + " items"));
        }
        view.inv.setItem(4, button(Material.EMERALD, "<green>Balance: " + Money.format(plugin.db().balance(p.getUniqueId())),
                "<gray>Left click: sell 1 · Shift+Left: sell all",
                "<gray>Right click: buy 1 · Shift+Right: buy a stack",
                "<gray>Prices drop when lots gets sold, then recover."));
        p.openInventory(view.inv);
    }

    private void openCategory(Player p, int index) {
        View view = new View(index);
        ShopCatalog.Category c = plugin.catalog().categories().get(index);
        view.inv = Bukkit.createInventory(view, 54, Msg.mm("<dark_purple>Shop <dark_gray>· <n>", Msg.v("n", c.name())));
        render(p, view);
        p.openInventory(view.inv);
    }

    private void render(Player p, View view) {
        ShopCatalog.Category c = plugin.catalog().categories().get(view.categoryIndex);
        view.inv.clear();
        for (int i = 0; i < c.items().size() && i < 45; i++) {
            ShopCatalog.Item it = c.items().get(i);
            long sell = unitSell(it);
            long full = it.worthCents();
            List<String> lore = new ArrayList<>();
            lore.add(!it.sellable() ? "<dark_gray>Buy only"
                    : "<gray>Sell: <green>" + Money.format(sell) + (sell < full * 95 / 100
                            ? " <dark_gray>(normally " + Money.format(full) + ")" : ""));
            lore.add(it.buyable() ? "<gray>Buy: <yellow>" + Money.format(buyCost(it, 1)) : "<dark_gray>Not sold by the shop");
            lore.add("<dark_gray>You have " + countPlain(p.getInventory(), it.material()));
            view.inv.setItem(i, button(it.material(), null, lore.toArray(String[]::new)));
        }
        view.inv.setItem(BACK, button(Material.ARROW, "<white>← Back"));
        view.inv.setItem(BACK + 4, button(Material.EMERALD, "<green>Balance: " + Money.format(plugin.db().balance(p.getUniqueId()))));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View view)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        if (view.categoryIndex == -1) {
            int[] slots = {10, 11, 12, 14, 15, 16, 19, 20, 21, 23, 24, 25};
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == slot && i < plugin.catalog().categories().size()) openCategory(p, i);
            }
            return;
        }
        if (slot == BACK) {
            openCategories(p);
            return;
        }
        List<ShopCatalog.Item> items = plugin.catalog().categories().get(view.categoryIndex).items();
        if (slot >= items.size() || slot >= 45) return;
        ShopCatalog.Item it = items.get(slot);
        ClickType click = e.getClick();
        if ((click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) && !it.sellable()) {
            Msg.err(p, "The shop doesn't buy <i> back. Right click to buy.", Msg.v("i", pretty(it.material())));
        } else if (click == ClickType.LEFT) {
            if (countPlain(p.getInventory(), it.material()) == 0) {
                Msg.err(p, "You have no <i> to sell.", Msg.v("i", pretty(it.material())));
            } else {
                long got = sell(p, it, 1);
                Msg.ok(p, "Sold <white>1× <i></white> for <green><m></green>.", Msg.v("i", pretty(it.material())), Msg.v("m", Money.format(got)));
            }
        } else if (click == ClickType.SHIFT_LEFT) {
            sellAllOf(p, it);
        } else if (click == ClickType.RIGHT) {
            buy(p, it, 1);
        } else if (click == ClickType.SHIFT_RIGHT) {
            buy(p, it, it.material().getMaxStackSize());
        }
        render(p, view);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    // --- helpers ---

    /** Counts unmodified items (no custom names, enchants or damage) in the main inventory. */
    private static int countPlain(PlayerInventory inv, Material m) {
        ItemStack plain = new ItemStack(m);
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) if (s != null && s.isSimilar(plain)) n += s.getAmount();
        return n;
    }

    private static void takeFromInventory(PlayerInventory inv, Material m, int count) {
        ItemStack plain = new ItemStack(m);
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && count > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || !s.isSimilar(plain)) continue;
            int take = Math.min(count, s.getAmount());
            count -= take;
            s.setAmount(s.getAmount() - take);
            contents[i] = s.getAmount() == 0 ? null : s;
        }
        inv.setStorageContents(contents);
    }

    private static ItemStack button(Material m, String name, String... lore) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            if (name != null) meta.displayName(plain(Msg.mm(name)));
            meta.lore(java.util.Arrays.stream(lore).map(l -> plain(Msg.mm(l))).toList());
        });
        return s;
    }

    private static Component plain(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    static String pretty(Material m) {
        String k = m.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return command.getName().equals("sell") && args.length == 1 ? List.of("hand", "all") : List.of();
    }
}
