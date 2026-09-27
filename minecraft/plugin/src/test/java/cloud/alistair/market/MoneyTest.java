package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.RoundingMode;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void parse() {
        assertEquals(OptionalLong.of(1200_50), Money.parse("$1,200.50"));
        assertEquals(OptionalLong.of(5_00), Money.parse("5"));
        assertEquals(OptionalLong.of(5_10), Money.parse("5.1"));
        assertTrue(Money.parse("0").isEmpty());
        assertTrue(Money.parse("-1").isEmpty());
        assertTrue(Money.parse("1.001").isEmpty());
        assertTrue(Money.parse("1e3").isEmpty() || Money.parse("1e3").getAsLong() == 1000_00);
        assertTrue(Money.parse("abc").isEmpty());
        assertTrue(Money.parse("99999999999999999999").isEmpty());
    }

    @Test
    void format() {
        assertEquals("$1,234.56", Money.format(1234_56));
        assertEquals("$0.05", Money.format(5));
    }

    @Test
    void shares() {
        assertEquals("0.25", Money.shares(250_000));
        assertEquals("2", Money.shares(2_000_000));
        assertEquals(OptionalLong.of(1_500_000), Money.parseShares("1.5"));
        assertTrue(Money.parseShares("0.0000001").isEmpty());
    }

    @Test
    void valueRounding() {
        // 1/3 share at $1.00: 33.33c → down 33, up 34.
        assertEquals(33, Money.value(333_333, 100, RoundingMode.DOWN));
        assertEquals(34, Money.value(333_333, 100, RoundingMode.UP));
        assertEquals(333_333, Money.sharesFor(100, 300));
    }
}
