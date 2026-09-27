package cloud.alistair.market;

import java.io.File;
import java.sql.SQLException;
import java.util.Objects;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public class MarketPlugin extends JavaPlugin {
    private Settings settings;
    private Db db;
    private PriceService prices;
    private ShopCatalog catalog;

    @Override
    public void onEnable() {
        if (!moveOldData()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        settings = Settings.from(getConfig());
        getDataFolder().mkdirs();
        try {
            db = new Db(new File(getDataFolder(), "market.db"));
        } catch (SQLException e) {
            getLogger().severe("Couldn't open database: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        prices = new PriceService(settings);
        if (!new File(getDataFolder(), "shop.yml").exists()) saveResource("shop.yml", false);
        catalog = ShopCatalog.load(new File(getDataFolder(), "shop.yml"), getLogger());
        bind("smp", new SmpCommand(this));

        EconomyCommands eco = new EconomyCommands(this);
        for (String c : new String[] {"balance", "pay", "baltop", "eco"}) bind(c, eco);
        StockCommands stocks = new StockCommands(this);
        bind("stock", stocks);
        bind("portfolio", stocks);
        MarketMenu market = new MarketMenu(this);
        bind("market", market);
        getServer().getPluginManager().registerEvents(market, this);
        ShopMenu shop = new ShopMenu(this);
        for (String c : new String[] {"shop", "sell", "worth"}) bind(c, shop);
        getServer().getPluginManager().registerEvents(shop, this);
        getServer().getPluginManager().registerEvents(new JoinBonus(this), this);
        getLogger().info("Shop loaded with " + catalog.size() + " items");
    }

    @Override
    public void onDisable() {
        if (db != null) {
            try {
                db.close();
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * This plugin used to be called NetheriteMarket. Moves its folder (balances, market, config) to
     * the new name. Returns false if that failed, so we don't start on an empty database.
     */
    private boolean moveOldData() {
        File old = new File(getDataFolder().getParentFile(), "NetheriteMarket");
        if (getDataFolder().exists() || !old.isDirectory()) return true;
        if (old.renameTo(getDataFolder())) {
            getLogger().info("Moved " + old + " to " + getDataFolder());
            return true;
        }
        getLogger().severe("Couldn't move " + old + " to " + getDataFolder() + ", move it by hand");
        return false;
    }

    /** Re-reads config.yml and shop.yml. Code changes still need a restart. */
    public void reload() {
        ShopMenu.closeAll(getServer());
        reloadConfig();
        settings = Settings.from(getConfig());
        prices = new PriceService(settings);
        catalog = ShopCatalog.load(new File(getDataFolder(), "shop.yml"), getLogger());
    }

    private void bind(String name, TabExecutor exec) {
        var cmd = Objects.requireNonNull(getCommand(name), name);
        cmd.setExecutor(exec);
        cmd.setTabCompleter(exec);
    }

    /** Runs on the main server thread. */
    public void sync(Runnable r) {
        if (getServer().isPrimaryThread()) r.run();
        else getServer().getScheduler().runTask(this, r);
    }

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
