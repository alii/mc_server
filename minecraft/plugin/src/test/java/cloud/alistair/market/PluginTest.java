package cloud.alistair.market;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Boots a fake Paper server with the plugin loaded and Yahoo replaced by canned prices. */
abstract class PluginTest {
    protected ServerMock server;
    protected MarketPlugin plugin;
    /** symbol -> raw Yahoo JSON. Missing symbols get Yahoo's "not found" reply. */
    protected final Map<String, String> yahoo = new HashMap<>();

    @BeforeEach
    void boot() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MarketPlugin.class);
        freshPrices();
    }

    /** New price service with an empty cache, reading from {@link #yahoo}. */
    private void freshPrices() {
        plugin.usePrices(new PriceService(plugin.settings(), symbol -> CompletableFuture.completedFuture(
                yahoo.getOrDefault(symbol, "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}"))));
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    protected void price(String symbol, double price) {
        price(symbol, price, "EQUITY", "USD");
    }

    protected void price(String symbol, double price, String type, String currency) {
        yahoo.put(symbol, """
                {"chart":{"result":[{"meta":{"currency":"%s","symbol":"%s","instrumentType":"%s",
                "regularMarketPrice":%s,"regularMarketChangePercent":1.5,"shortName":"%s Inc.",
                "currentTradingPeriod":{"regular":{"start":0,"end":9999999999}}}}],"error":null}}
                """.formatted(currency, symbol, type, price, symbol));
        freshPrices();
    }

    /** Adds a player and throws away their welcome message. */
    protected PlayerMock player(String name) {
        PlayerMock p = server.addPlayer(name);
        said(p);
        return p;
    }

    /** Runs a command and returns what the player was told. */
    protected String run(PlayerMock p, String command) {
        said(p);
        p.performCommand(command);
        return saidAll(p);
    }

    /** The slot holding {@code m} in the menu the player has open, or fails. */
    protected int slotOf(PlayerMock p, org.bukkit.Material m) {
        var top = p.getOpenInventory().getTopInventory();
        for (int i = 0; i < top.getSize(); i++) {
            var s = top.getItem(i);
            if (s != null && s.getType() == m) return i;
        }
        throw new AssertionError(m + " is not in the open menu");
    }

    protected org.bukkit.event.inventory.InventoryClickEvent click(PlayerMock p, int slot, org.bukkit.event.inventory.ClickType type) {
        return p.simulateInventoryClick(p.getOpenInventory(), type, slot);
    }

    protected int count(PlayerMock p, org.bukkit.Material m) {
        int n = 0;
        for (var s : p.getInventory().getContents()) if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    protected long balance(PlayerMock p) {
        return plugin.db().balance(p.getUniqueId());
    }

    protected void setBalance(PlayerMock p, long cents) {
        plugin.db().set(p.getUniqueId(), p.getName(), cents);
    }

    /** Every chat message the player has received since the last call, as plain text. */
    protected List<String> said(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) out.add(PlainTextComponentSerializer.plainText().serialize(c));
        return out;
    }

    protected String saidAll(PlayerMock p) {
        return String.join("\n", said(p));
    }
}
