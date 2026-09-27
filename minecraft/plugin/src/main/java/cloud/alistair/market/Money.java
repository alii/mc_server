package cloud.alistair.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.OptionalLong;

/** Money is always a long number of cents. Shares are always a long number of micro-shares (1e-6). */
public final class Money {
    public static final long MICROS = 1_000_000L;

    private Money() {}

    public static String format(long cents) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        return f.format(BigDecimal.valueOf(cents, 2));
    }

    /** Parses "12", "12.5", "$1,200.99". Rejects zero, negatives and more than 2 decimals. */
    public static OptionalLong parse(String raw) {
        try {
            BigDecimal d = new BigDecimal(raw.replace("$", "").replace(",", ""));
            if (d.signum() <= 0 || d.scale() > 2) return OptionalLong.empty();
            return OptionalLong.of(d.movePointRight(2).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static String shares(long micros) {
        return BigDecimal.valueOf(micros, 6).stripTrailingZeros().toPlainString();
    }

    /** Parses a share count like "2" or "0.5" into micro-shares. */
    public static OptionalLong parseShares(String raw) {
        try {
            BigDecimal d = new BigDecimal(raw);
            if (d.signum() <= 0 || d.scale() > 6) return OptionalLong.empty();
            return OptionalLong.of(d.movePointRight(6).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** Value in cents of {@code micros} shares at {@code priceCents} each. */
    public static long value(long micros, long priceCents, RoundingMode mode) {
        return BigDecimal.valueOf(micros).multiply(BigDecimal.valueOf(priceCents))
                .divide(BigDecimal.valueOf(MICROS), 0, mode).longValueExact();
    }

    /** How many micro-shares {@code cents} buys at {@code priceCents} each (rounded down). */
    public static long sharesFor(long cents, long priceCents) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(MICROS))
                .divide(BigDecimal.valueOf(priceCents), 0, RoundingMode.DOWN).longValueExact();
    }

    public static long percent(long cents, double pct, RoundingMode mode) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(pct))
                .divide(BigDecimal.valueOf(100), 0, mode).longValueExact();
    }
}
