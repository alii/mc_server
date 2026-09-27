package cloud.alistair.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Real prices from Yahoo Finance's public chart endpoint, cached for a short while. */
public final class PriceService {
    public record Quote(String symbol, String name, String type, long priceCents, double changePercent,
            boolean marketOpen, long fetchedAt) {}

    /** A reason a ticker can't be traded, shown to the player as-is. */
    public static final class QuoteException extends RuntimeException {
        public QuoteException(String message) {
            super(message, null, false, false);
        }
    }

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9.\\-]{1,10}");

    /** Gets Yahoo's chart JSON for a ticker. Swapped out in tests. */
    @FunctionalInterface
    public interface Fetcher {
        CompletableFuture<String> fetch(String symbol);
    }

    private final Map<String, Quote> cache = new ConcurrentHashMap<>();
    private final Fetcher fetcher;
    private volatile Settings settings;

    public PriceService(Settings settings) {
        this(settings, yahoo());
    }

    public PriceService(Settings settings, Fetcher fetcher) {
        this.settings = settings;
        this.fetcher = fetcher;
    }

    private static Fetcher yahoo() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return symbol -> http.sendAsync(HttpRequest.newBuilder(URI.create(
                                "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?interval=1d&range=1d"))
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(Duration.ofSeconds(8))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    public void settings(Settings settings) {
        this.settings = settings;
    }

    public static String normalize(String raw) {
        return raw.toUpperCase(Locale.ROOT).replace("$", "");
    }

    /** Last cached price, without hitting the network. Used by the leaderboard. */
    public Quote cached(String symbol) {
        return cache.get(symbol);
    }

    /** Completes off the main thread. Fails with {@link QuoteException} for untradeable tickers. */
    public CompletableFuture<Quote> quote(String rawSymbol) {
        String symbol = normalize(rawSymbol);
        if (!SYMBOL.matcher(symbol).matches()) {
            return CompletableFuture.failedFuture(new QuoteException("That doesn't look like a ticker."));
        }
        Settings s = settings;
        if (s.blockedSymbols().contains(symbol)) {
            return CompletableFuture.failedFuture(new QuoteException(symbol + " is banned on this server (too wild)."));
        }
        Quote hit = cache.get(symbol);
        if (hit != null && System.currentTimeMillis() - hit.fetchedAt() < s.quoteCacheSeconds() * 1000L) {
            return CompletableFuture.completedFuture(hit);
        }
        return fetcher.fetch(symbol).thenApply(body -> {
            Quote q = parse(symbol, body, s);
            cache.put(symbol, q);
            return q;
        });
    }

    private static Quote parse(String symbol, String body, Settings s) {
        JsonObject chart;
        try {
            chart = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("chart");
        } catch (RuntimeException e) {
            throw new QuoteException("Couldn't read a price for " + symbol + " right now.");
        }
        if (chart == null || !chart.has("result") || !chart.get("result").isJsonArray()
                || chart.getAsJsonArray("result").isEmpty()) {
            throw new QuoteException("No stock called " + symbol + ".");
        }
        JsonObject m = chart.getAsJsonArray("result").get(0).getAsJsonObject().getAsJsonObject("meta");
        String currency = str(m, "currency");
        String type = str(m, "instrumentType");
        if (!"USD".equals(currency)) throw new QuoteException(symbol + " isn't priced in US dollars.");
        if (!s.allowedTypes().contains(type)) throw new QuoteException(symbol + " is a " + type + ", only stocks and ETFs allowed.");
        if (!m.has("regularMarketPrice")) throw new QuoteException("No price for " + symbol + " right now.");
        long cents = Math.round(m.get("regularMarketPrice").getAsDouble() * 100);
        if (cents < s.minPriceCents()) throw new QuoteException(symbol + " is under " + Money.format(s.minPriceCents()) + " (no penny stocks).");
        String name = m.has("shortName") ? str(m, "shortName") : symbol;
        double change = m.has("regularMarketChangePercent") ? m.get("regularMarketChangePercent").getAsDouble() : 0;
        return new Quote(symbol, name, type, cents, change, isOpen(m), System.currentTimeMillis());
    }

    private static boolean isOpen(JsonObject m) {
        try {
            JsonObject reg = m.getAsJsonObject("currentTradingPeriod").getAsJsonObject("regular");
            long now = System.currentTimeMillis() / 1000;
            return now >= reg.get("start").getAsLong() && now < reg.get("end").getAsLong();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
