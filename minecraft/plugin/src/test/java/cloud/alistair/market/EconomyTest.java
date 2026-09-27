package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class EconomyTest extends PluginTest {
    @Test
    void payMovesMoney() {
        PlayerMock a = player("steve"), b = player("alex");
        run(a, "pay alex 25.50");
        assertEquals(74_50, balance(a));
        assertEquals(125_50, balance(b));
        assertTrue(saidAll(b).contains("steve sent you $25.50"));
    }

    @Test
    void cantPayMoreThanYouHave() {
        PlayerMock a = player("steve"), b = player("alex");
        assertTrue(run(a, "pay alex 100.01").contains("don't have"));
        assertEquals(100_00, balance(a));
        assertEquals(100_00, balance(b));
    }

    @Test
    void cantPayYourselfOrNegativeOrNonsense() {
        PlayerMock a = player("steve");
        player("alex");
        assertTrue(run(a, "pay steve 5").contains("yourself"));
        assertTrue(run(a, "pay alex -5").contains("not an amount"));
        assertTrue(run(a, "pay alex 0").contains("not an amount"));
        assertTrue(run(a, "pay alex 1.001").contains("not an amount"));
        assertTrue(run(a, "pay alex lots").contains("not an amount"));
        assertTrue(run(a, "pay nobody 5").contains("Never seen"));
        assertEquals(100_00, balance(a));
    }

    @Test
    void ecoIsOpsOnly() {
        PlayerMock a = player("steve");
        run(a, "eco give steve 1000");
        assertEquals(100_00, balance(a));
        a.setOp(true);
        run(a, "eco give steve 1000");
        assertEquals(1100_00, balance(a));
        run(a, "eco take steve 999999");
        assertEquals(0, balance(a), "take never goes below zero");
        run(a, "eco set steve 42");
        assertEquals(42_00, balance(a));
    }

    @Test
    void baltopIsSortedByNetWorth() {
        PlayerMock a = player("steve"), b = player("alex"), c = player("sam");
        setBalance(a, 50_00);
        setBalance(b, 500_00);
        setBalance(c, 200_00);
        price("AAPL", 100);
        setBalance(a, 1000_00);
        run(a, "stock buy AAPL 5"); // a: 495 cash + 500 stock
        String out = run(a, "baltop");
        int ia = out.indexOf("steve"), ib = out.indexOf("alex"), ic = out.indexOf("sam");
        assertTrue(ia < ib && ib < ic, out);
    }
}
