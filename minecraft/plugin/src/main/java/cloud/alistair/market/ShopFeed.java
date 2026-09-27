package cloud.alistair.market;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Tells chat what people buy and sell at the shop. A player's trades are added up for a few
 * seconds and posted as one line, so clicking 20 times doesn't post 20 messages.
 */
final class ShopFeed {
    private static final String PREFIX = "<dark_gray>[<gradient:#b86bff:#ff6bd6>shop</gradient>]</dark_gray> <gray>";
    private static final int MAX_ITEMS_LISTED = 3;

    private static final class Batch {
        final String name;
        final Map<Material, Integer> sold = new LinkedHashMap<>();
        final Map<Material, Integer> bought = new LinkedHashMap<>();
        long soldCents, boughtCents;

        Batch(String name) {
            this.name = name;
        }
    }

    private final SmpCore core;
    private final Map<UUID, Batch> pending = new HashMap<>();

    ShopFeed(SmpCore core) {
        this.core = core;
    }

    void sold(Player p, Material m, int count, long cents) {
        Batch b = batch(p);
        b.sold.merge(m, count, Integer::sum);
        b.soldCents += cents;
    }

    void bought(Player p, Material m, int count, long cents) {
        Batch b = batch(p);
        b.bought.merge(m, count, Integer::sum);
        b.boughtCents += cents;
    }

    private Batch batch(Player p) {
        return pending.computeIfAbsent(p.getUniqueId(), id -> {
            long ticks = Math.max(1, core.settings().shopFeedSeconds()) * 20L;
            core.getServer().getScheduler().runTaskLater(core.host(), () -> flush(id), ticks);
            return new Batch(p.getName());
        });
    }

    private void flush(UUID id) {
        Batch b = pending.remove(id);
        if (b == null) return;
        long min = core.settings().shopFeedMinCents();
        if (!b.sold.isEmpty() && b.soldCents >= min) post(b.name, "sold", b.sold, b.soldCents);
        if (!b.bought.isEmpty() && b.boughtCents >= min) post(b.name, "bought", b.bought, b.boughtCents);
    }

    private void post(String name, String verb, Map<Material, Integer> items, long cents) {
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (var e : items.entrySet()) {
            if (shown == MAX_ITEMS_LISTED) {
                list.append(" and ").append(items.size() - shown).append(" more");
                break;
            }
            if (shown > 0) list.append(", ");
            list.append(e.getValue()).append("× ").append(ShopMenu.pretty(e.getKey()));
            shown++;
        }
        core.getServer().broadcast(Msg.mm(PREFIX + "<white><p></white> " + verb + " <white><items></white> for <green><m></green>",
                Msg.v("p", name), Msg.v("items", list.toString()), Msg.v("m", Money.format(cents))));
    }
}
