package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class StockTest extends PluginTest {
    private long shares(PlayerMock p, String symbol) {
        return plugin.db().holding(p.getUniqueId(), symbol).map(Db.Holding::micros).orElse(0L);
    }

    @Test
    void quoteShowsTheRealPrice() {
        PlayerMock p = player("steve");
        price("AAPL", 341.07);
        String out = run(p, "stock AAPL");
        assertTrue(out.contains("AAPL") && out.contains("$341.07"), out);
    }

    @Test
    void lowercaseAndDollarSignWork() {
        PlayerMock p = player("steve");
        price("AAPL", 341.07);
        assertTrue(run(p, "stock $aapl").contains("$341.07"));
    }

    @Test
    void buyingSharesChargesPriceAndFee() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 300);
        run(p, "stock buy AAPL 2");
        assertEquals(1000_00 - 606_00, balance(p)); // $600 + 1% fee
        assertEquals(2_000_000, shares(p, "AAPL"));
    }

    @Test
    void fractionalShares() {
        PlayerMock p = player("steve");
        price("AAPL", 300);
        run(p, "stock buy AAPL 0.25");
        assertEquals(250_000, shares(p, "AAPL"));
        assertEquals(100_00 - 75_75, balance(p));
    }

    @Test
    void buyingByDollarsNeverGoesOverBudget() {
        PlayerMock p = player("steve");
        price("AAPL", 341.07);
        run(p, "stock buy AAPL $50");
        long spent = 100_00 - balance(p);
        assertTrue(spent <= 50_00 && spent > 49_90, "spent " + spent);
        assertTrue(shares(p, "AAPL") > 0);
    }

    @Test
    void cantBuyWithoutTheMoney() {
        PlayerMock p = player("steve");
        price("AAPL", 300);
        assertTrue(run(p, "stock buy AAPL 1").contains("costs"));
        assertEquals(100_00, balance(p));
        assertEquals(0, shares(p, "AAPL"));
    }

    @Test
    void sellingAllAfterARisePaysProfitMinusFee() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 300);
        run(p, "stock buy AAPL 1"); // pays $303
        price("AAPL", 330);
        String out = run(p, "stock sell AAPL all"); // gets $330 - $3.30
        assertEquals(1000_00 - 303_00 + 326_70, balance(p));
        assertEquals(0, shares(p, "AAPL"));
        assertTrue(out.contains("+$23.70"), out);
    }

    @Test
    void sellingAtALossShowsTheLoss() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 300);
        run(p, "stock buy AAPL 1");
        price("AAPL", 250);
        assertTrue(run(p, "stock sell AAPL all").contains("-$55.50"));
    }

    @Test
    void partialSellKeepsTheRest() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 100);
        run(p, "stock buy AAPL 3");
        run(p, "stock sell AAPL 1");
        assertEquals(2_000_000, shares(p, "AAPL"));
        assertEquals(202_00, plugin.db().holding(p.getUniqueId(), "AAPL").orElseThrow().costCents());
    }

    @Test
    void cantSellMoreThanYouOwn() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 100);
        run(p, "stock buy AAPL 1");
        long before = balance(p);
        assertTrue(run(p, "stock sell AAPL 2").contains("only have"));
        assertEquals(before, balance(p));
        assertEquals(1_000_000, shares(p, "AAPL"));
    }

    @Test
    void cantSellWhatYouNeverBought() {
        PlayerMock p = player("steve");
        price("MSFT", 400);
        assertTrue(run(p, "stock sell MSFT all").contains("don't own"));
        assertEquals(100_00, balance(p));
    }

    @Test
    void wildTickersAreBanned() {
        PlayerMock p = player("steve");
        price("TQQQ", 80);
        assertTrue(run(p, "stock buy TQQQ 1").contains("banned"));
        assertEquals(100_00, balance(p));
    }

    @Test
    void cryptoAndForeignAndPennyStocksAreRefused() {
        PlayerMock p = player("steve");
        price("BTC-USD", 60000, "CRYPTOCURRENCY", "USD");
        price("SHEL.L", 30, "EQUITY", "GBp");
        price("PENY", 2.50);
        assertTrue(run(p, "stock buy BTC-USD 0.001").contains("only stocks and ETFs"));
        assertTrue(run(p, "stock buy SHEL.L 1").contains("US dollars"));
        assertTrue(run(p, "stock buy PENY 1").contains("penny"));
        assertEquals(100_00, balance(p));
    }

    @Test
    void unknownTickers() {
        PlayerMock p = player("steve");
        assertTrue(run(p, "stock NOPE").contains("No stock called NOPE"));
        assertTrue(run(p, "stock <red>hi").contains("doesn't look like a ticker"));
    }

    @Test
    void portfolioAddsUp() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        price("AAPL", 100);
        price("MSFT", 200);
        run(p, "stock buy AAPL 1");
        run(p, "stock buy MSFT 1");
        String out = run(p, "portfolio");
        assertTrue(out.contains("AAPL") && out.contains("MSFT"), out);
        assertTrue(out.contains("Total $1,000.00".replace("1,000.00", String.format("%,.2f", (balance(p) + 300_00) / 100.0))), out);
    }
}
