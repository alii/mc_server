package cloud.alistair.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** /market: a paged chest menu of player listings. Click to buy, or click your own to take it back. */
public final class MarketMenu implements TabExecutor, Listener {
    private static final int PER_PAGE = 45;
    private static final int PREV = 45, TOGGLE = 48, INFO = 49, NEXT = 53;

    private enum Mode { BROWSE, MINE }

    private static final class View implements InventoryHolder {
        final Mode mode;
        final int page;
        final long[] ids = new long[PER_PAGE];
        Inventory inv;

        View(Mode mode, int page) {
            this.mode = mode;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final MarketPlugin plugin;

    public MarketMenu(MarketPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length == 0) {
            open(p, Mode.BROWSE, 0);
        } else if (args[0].equalsIgnoreCase("mine")) {
            open(p, Mode.MINE, 0);
        } else if (args[0].equalsIgnoreCase("sell") && args.length == 2) {
            sell(p, args[1]);
        } else {
            Msg.err(p, "Usage: /market | /market sell <price> | /market mine");
        }
        return true;
    }

    private void sell(Player p, String priceArg) {
        OptionalLong price = Money.parse(priceArg);
        if (price.isEmpty()) {
            Msg.err(p, "That's not a price.");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            Msg.err(p, "Hold the item you want to sell.");
            return;
        }
        int max = plugin.settings().maxListingsPerPlayer();
        if (plugin.db().listings(p.getUniqueId()).size() >= max) {
            Msg.err(p, "You already have <n> listings. Take some back with /market mine.", Msg.v("n", String.valueOf(max)));
            return;
        }
        p.getInventory().setItemInMainHand(null);
        plugin.db().addListing(p.getUniqueId(), p.getName(), hand.serializeAsBytes(), price.getAsLong());
        Msg.ok(p, "Listed <white><n>× <i></white> for <green><m></green>. Seller fee on sale: <f>%.",
                Msg.v("n", String.valueOf(hand.getAmount())), Msg.v("i", itemName(hand)),
                Msg.v("m", Money.format(price.getAsLong())), Msg.v("f", String.valueOf(plugin.settings().marketFeePercent())));
    }

    private void open(Player p, Mode mode, int page) {
        List<Db.Listing> all = plugin.db().listings(mode == Mode.MINE ? p.getUniqueId() : null);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(Math.max(0, page), pages - 1);
        View view = new View(mode, page);
        Component title = Msg.mm(mode == Mode.MINE ? "<dark_purple>My listings" : "<dark_purple>Market")
                .append(Msg.mm(" <dark_gray>(<n>/<t>)", Msg.v("n", String.valueOf(page + 1)), Msg.v("t", String.valueOf(pages))));
        view.inv = Bukkit.createInventory(view, 54, title);

        for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < all.size(); i++) {
            Db.Listing l = all.get(page * PER_PAGE + i);
            ItemStack shown = ItemStack.deserializeBytes(l.item());
            boolean own = l.seller().equals(p.getUniqueId());
            List<Component> lore = new ArrayList<>(Optional.ofNullable(shown.lore()).orElse(List.of()));
            lore.add(Component.empty());
            lore.add(plain(Msg.mm("<gray>Price: <green><m>", Msg.v("m", Money.format(l.priceCents())))));
            lore.add(plain(Msg.mm("<gray>Seller: <white><s>", Msg.v("s", l.sellerName()))));
            lore.add(plain(Msg.mm(own ? "<yellow>Click to take back" : "<aqua>Click to buy")));
            shown.lore(lore);
            view.inv.setItem(i, shown);
            view.ids[i] = l.id();
        }
        if (page > 0) view.inv.setItem(PREV, button(Material.ARROW, "<white>← Previous page"));
        if (page < pages - 1) view.inv.setItem(NEXT, button(Material.ARROW, "<white>Next page →"));
        view.inv.setItem(TOGGLE, mode == Mode.MINE ? button(Material.CHEST, "<white>Browse everything")
                : button(Material.ENDER_CHEST, "<white>My listings"));
        view.inv.setItem(INFO, button(Material.EMERALD, "<green>Balance: " + Money.format(plugin.db().balance(p.getUniqueId())),
                "<gray>Sell: hold an item, /market sell [price]"));
        p.openInventory(view.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View view)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() >= 54 || e.getRawSlot() < 0) return;
        int slot = e.getRawSlot();
        switch (slot) {
            case PREV -> open(p, view.mode, view.page - 1);
            case NEXT -> open(p, view.mode, view.page + 1);
            case TOGGLE -> open(p, view.mode == Mode.MINE ? Mode.BROWSE : Mode.MINE, 0);
            default -> {
                if (slot < PER_PAGE && view.ids[slot] != 0) {
                    click(p, view.ids[slot]);
                    open(p, view.mode, view.page);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    private void click(Player p, long id) {
        Optional<Db.Listing> found = plugin.db().listing(id);
        if (found.isEmpty()) {
            Msg.err(p, "Someone got there first. That's gone.");
            return;
        }
        Db.Listing l = found.get();
        ItemStack item = ItemStack.deserializeBytes(l.item());
        if (l.seller().equals(p.getUniqueId())) {
            plugin.db().removeListing(id);
            EconomyCommands.give(p, item);
            Msg.ok(p, "Took back <white><i></white>.", Msg.v("i", itemName(item)));
            return;
        }
        long balance = plugin.db().balance(p.getUniqueId());
        if (balance < l.priceCents()) {
            Msg.err(p, "That's <m>. You have <b>.", Msg.v("m", Money.format(l.priceCents())), Msg.v("b", Money.format(balance)));
            return;
        }
        long fee = Money.percent(l.priceCents(), plugin.settings().marketFeePercent(), java.math.RoundingMode.UP);
        plugin.db().removeListing(id);
        plugin.db().add(p.getUniqueId(), p.getName(), -l.priceCents(), "market-buy", "#" + id + " from " + l.sellerName());
        plugin.db().add(l.seller(), l.sellerName(), l.priceCents() - fee, "market-sale", "#" + id + " to " + p.getName());
        EconomyCommands.give(p, item);
        Msg.ok(p, "Bought <white><n>× <i></white> for <green><m></green>.",
                Msg.v("n", String.valueOf(item.getAmount())), Msg.v("i", itemName(item)), Msg.v("m", Money.format(l.priceCents())));
        Player seller = Bukkit.getPlayer(l.seller());
        if (seller != null) {
            Msg.ok(seller, "<white><p></white> bought your <white><i></white>. You got <green><m></green>.",
                    Msg.v("p", p.getName()), Msg.v("i", itemName(item)), Msg.v("m", Money.format(l.priceCents() - fee)));
        }
    }

    private static ItemStack button(Material m, String name, String... lore) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.displayName(plain(Msg.mm(name)));
            meta.lore(java.util.Arrays.stream(lore).map(l -> plain(Msg.mm(l))).toList());
        });
        return s;
    }

    private static Component plain(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static String itemName(ItemStack s) {
        String key = s.getType().getKey().getKey().replace('_', ' ');
        return s.hasItemMeta() && s.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(s.getItemMeta().displayName())
                : key;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? List.of("sell", "mine") : List.of();
    }
}
