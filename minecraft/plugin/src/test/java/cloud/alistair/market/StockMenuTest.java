package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.List;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class StockMenuTest extends PluginTest {
    /** Name and lore of an item as plain text. */
    static String text(ItemStack item) {
        assertNotNull(item);
        var plain = PlainTextComponentSerializer.plainText();
        StringBuilder out = new StringBuilder(plain.serialize(item.getItemMeta().displayName()));
        List<Component> lore = item.lore();
        if (lore != null) for (Component c : lore) out.append('\n').append(plain.serialize(c));
        return out.toString();
    }

    private Inventory top(PlayerMock p) {
        return p.getOpenInventory().getTopInventory();
    }

    private long shares(PlayerMock p, String symbol) {
        return plugin.db().holding(p.getUniqueId(), symbol).map(Db.Holding::micros).orElse(0L);
    }

    @Test
    void homeListsPopularStocksWithPrices() {
        PlayerMock p = player("steve");
        price("AAPL", 341.07);
        run(p, "stock");
        String aapl = null;
        for (int slot : StockMenu.GRID) {
            ItemStack item = top(p).getItem(slot);
            if (item != null && text(item).startsWith("AAPL")) aapl = text(item);
        }
        assertNotNull(aapl, "AAPL isn't on the home screen");
        assertTrue(aapl.contains("$341.07") && aapl.contains("+1.50%"), aapl);
    }

    @Test
    void clickAStockThenBuyAndSellWithButtons() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 100);
        run(p, "stock");
        int slot = -1;
        for (int s : StockMenu.GRID) {
            ItemStack item = top(p).getItem(s);
            if (item != null && text(item).startsWith("AAPL")) slot = s;
        }
        click(p, slot, ClickType.LEFT);
        assertTrue(text(top(p).getItem(StockMenu.INFO)).startsWith("AAPL"));

        click(p, StockMenu.BUY_100, ClickType.LEFT);
        long spent = 1000_00 - balance(p);
        assertTrue(spent <= 100_00 && spent > 99_00, "spent " + spent);
        assertTrue(text(top(p).getItem(StockMenu.INFO)).contains("You own"), "screen didn't update");

        long before = shares(p, "AAPL");
        click(p, StockMenu.SELL_HALF, ClickType.LEFT);
        assertEquals(before - before / 2, shares(p, "AAPL"));
        click(p, StockMenu.SELL_ALL, ClickType.LEFT);
        assertEquals(0, shares(p, "AAPL"));
    }

    @Test
    void sellButtonsAreGreyWhenYouOwnNothing() {
        PlayerMock p = player("steve");
        price("AAPL", 100);
        run(p, "stock AAPL");
        assertTrue(text(top(p).getItem(StockMenu.SELL_ALL)).contains("Nothing to sell"));
        click(p, StockMenu.SELL_ALL, ClickType.LEFT);
        assertEquals(100_00, balance(p));
    }

    @Test
    void portfolioShowsWhatYouOwn() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("MSFT", 200);
        run(p, "stock buy MSFT 1");
        run(p, "stock");
        click(p, StockMenu.PORTFOLIO, ClickType.LEFT);
        assertTrue(text(top(p).getItem(0)).startsWith("MSFT"));
        click(p, 0, ClickType.LEFT);
        assertTrue(text(top(p).getItem(StockMenu.INFO)).startsWith("MSFT"));
    }

    @Test
    void searchTakesATickerFromChatWithoutPostingIt() {
        PlayerMock p = player("steve");
        price("VOO", 500);
        run(p, "stock");
        click(p, StockMenu.SEARCH, ClickType.LEFT);
        AsyncChatEvent chat = new AsyncChatEvent(false, p, new HashSet<net.kyori.adventure.audience.Audience>(server.getOnlinePlayers()),
                ChatRenderer.defaultRenderer(), Component.text("voo"), Component.text("voo"), null);
        server.getPluginManager().callEvent(chat);
        assertTrue(chat.isCancelled());
        assertTrue(text(top(p).getItem(StockMenu.INFO)).startsWith("VOO"));
    }

    @Test
    void normalChatIsLeftAlone() {
        PlayerMock p = player("steve");
        AsyncChatEvent chat = new AsyncChatEvent(false, p, new HashSet<net.kyori.adventure.audience.Audience>(server.getOnlinePlayers()),
                ChatRenderer.defaultRenderer(), Component.text("hi"), Component.text("hi"), null);
        server.getPluginManager().callEvent(chat);
        assertFalse(chat.isCancelled());
    }

    @Test
    void companyNamesCantInjectFormatting() {
        PlayerMock p = player("steve");
        yahoo.put("EVIL", """
                {"chart":{"result":[{"meta":{"currency":"USD","symbol":"EVIL","instrumentType":"EQUITY",
                "regularMarketPrice":50,"shortName":"<red>Evil</red> Corp"}}],"error":null}}
                """);
        run(p, "stock EVIL");
        assertTrue(text(top(p).getItem(StockMenu.INFO)).contains("<red>Evil</red> Corp"));
    }
}
