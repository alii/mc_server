package cloud.alistair.market;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

public record Settings(
        long startingCents,
        double buyMultiplier,
        long saturationCents,
        double recoveryHalfLifeHours,
        double tradeFeePercent,
        double marketFeePercent,
        boolean marketHoursOnly,
        int quoteCacheSeconds,
        Set<String> allowedTypes,
        long minPriceCents,
        Set<String> blockedSymbols,
        int maxListingsPerPlayer) {

    public static Settings from(FileConfiguration c) {
        return new Settings(
                Math.round(c.getDouble("starting-balance") * 100),
                c.getDouble("buy-multiplier"),
                Math.round(c.getDouble("saturation-dollars") * 100),
                c.getDouble("recovery-half-life-hours"),
                c.getDouble("trade-fee-percent"),
                c.getDouble("market-fee-percent"),
                c.getBoolean("market-hours-only"),
                c.getInt("quote-cache-seconds"),
                upper(c.getStringList("allowed-types")),
                Math.round(c.getDouble("min-price") * 100),
                upper(c.getStringList("blocked-symbols")),
                c.getInt("max-listings-per-player"));
    }

    private static Set<String> upper(Iterable<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toUpperCase(Locale.ROOT));
        return out;
    }
}
