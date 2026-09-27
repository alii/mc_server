package cloud.alistair.market;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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
        int maxListingsPerPlayer,
        Map<String, Material> popularStocks,
        int tipIntervalMinutes,
        List<String> tips,
        int shopFeedSeconds,
        long shopFeedMinCents,
        boolean announceSleep,
        boolean announceMarket) {

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
                c.getInt("max-listings-per-player"),
                popular(c.getConfigurationSection("popular-stocks")),
                c.getInt("tip-interval-minutes"),
                List.copyOf(c.getStringList("tips")),
                c.getInt("shop-feed-seconds"),
                Math.round(c.getDouble("shop-feed-min-dollars") * 100),
                c.getBoolean("announce-sleep"),
                c.getBoolean("announce-market"));
    }

    /** Ticker to icon, in config order. Unknown items fall back to paper. */
    private static Map<String, Material> popular(ConfigurationSection sec) {
        Map<String, Material> out = new LinkedHashMap<>();
        if (sec == null) return out;
        for (String symbol : sec.getKeys(false)) {
            Material m = Material.matchMaterial(sec.getString(symbol, ""));
            out.put(PriceService.normalize(symbol), m != null && m.isItem() ? m : Material.PAPER);
        }
        return out;
    }

    private static Set<String> upper(Iterable<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) out.add(s.toUpperCase(Locale.ROOT));
        return out;
    }
}
