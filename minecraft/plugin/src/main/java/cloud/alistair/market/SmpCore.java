package cloud.alistair.market;

import cloud.alistair.smp.loader.Module;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Everything the plugin does. Started by the loader, and replaced wholesale on {@code /smp reload},
 * so config, shop prices and code changes all apply without a restart.
 */
public final class SmpCore implements Module {
    private JavaPlugin host;
    private YamlConfiguration config;
    private Settings settings;
    private Db db;
    private PriceService prices;
    private ShopCatalog catalog;
    private volatile boolean enabled;

    @Override
    public void enable(JavaPlugin host) throws Exception {
        this.host = host;
        getDataFolder().mkdirs();
        settings = loadSettings();
        db = new Db(new File(getDataFolder(), "market.db"));
        prices = new PriceService(settings);
        saveResourceIfMissing("shop.yml");
        catalog = ShopCatalog.load(new File(getDataFolder(), "shop.yml"), getLogger());
        enabled = true;

        EconomyCommands eco = new EconomyCommands(this);
        for (String c : new String[] {"balance", "pay", "baltop", "eco"}) bind(c, eco);
        StockCommands stocks = new StockCommands(this);
        bind("stock", stocks);
        bind("portfolio", stocks);
        listen(stocks.menu());
        MarketMenu market = new MarketMenu(this);
        bind("market", market);
        listen(market);
        ShopMenu shop = new ShopMenu(this);
        for (String c : new String[] {"shop", "sell", "worth"}) bind(c, shop);
        listen(shop);
        listen(new JoinBonus(this));
        if (settings.announceSleep()) listen(new SleepNotice(this));
        Tips.start(this);
        getLogger().info("Core loaded. Shop has " + catalog.size() + " items");
    }

    @Override
    public void disable() {
        enabled = false;
        if (db != null) {
            try {
                db.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // --- config ---

    /** Reads config.yml, adding settings from newer versions while keeping what's already set. */
    private Settings loadSettings() throws IOException {
        saveResourceIfMissing("config.yml");
        File file = new File(getDataFolder(), "config.yml");
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("config.yml"))) {
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        config.options().copyDefaults(true);
        config.save(file);
        return Settings.from(config);
    }

    private void saveResourceIfMissing(String name) throws IOException {
        File out = new File(getDataFolder(), name);
        if (out.exists()) return;
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(name), name)) {
            Files.copy(in, out.toPath());
        }
    }

    public YamlConfiguration getConfig() {
        return config;
    }

    // --- wiring, all owned by the host so the loader can undo it ---

    private void bind(String name, TabExecutor exec) {
        var cmd = Objects.requireNonNull(host.getCommand(name), name);
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
    }

    private void listen(Listener l) {
        getServer().getPluginManager().registerEvents(l, host);
    }

    /** Runs on the main server thread. Dropped if this core was reloaded away in the meantime. */
    public void sync(Runnable r) {
        if (!enabled) return;
        if (getServer().isPrimaryThread()) r.run();
        else getServer().getScheduler().runTask(host, () -> {
            if (enabled) r.run();
        });
    }

    public JavaPlugin host() {
        return host;
    }

    public Server getServer() {
        return host.getServer();
    }

    public Logger getLogger() {
        return host.getLogger();
    }

    public File getDataFolder() {
        return host.getDataFolder();
    }

    // --- services ---

    public Settings settings() {
        return settings;
    }

    public ShopCatalog catalog() {
        return catalog;
    }

    public Db db() {
        return db;
    }

    /** Tests swap in canned prices. */
    public void usePrices(PriceService prices) {
        this.prices = prices;
    }

    public PriceService prices() {
        return prices;
    }
}
