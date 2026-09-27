package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ReloadTest extends PluginTest {
    private PlayerMock admin() {
        PlayerMock a = player("admin");
        a.setOp(true);
        return a;
    }

    /** Puts the core.jar Gradle just built where the loader looks for it. */
    private void installCoreJar() throws Exception {
        Path target = new File(loader.getDataFolder(), "core/core.jar").toPath();
        Files.createDirectories(target.getParent());
        Files.copy(Path.of(System.getProperty("smp.coreJar")), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private int clickListeners() {
        return (int) java.util.Arrays.stream(InventoryClickEvent.getHandlerList().getRegisteredListeners())
                .filter(l -> l.getPlugin() == loader)
                .count();
    }

    @Test
    void reloadPicksUpEditedFiles() throws Exception {
        File shopFile = new File(plugin.getDataFolder(), "shop.yml");
        YamlConfiguration shop = YamlConfiguration.loadConfiguration(shopFile);
        String diamond = shop.getConfigurationSection("categories").getKeys(false).stream()
                .map(k -> "categories." + k + ".items.DIAMOND")
                .filter(shop::contains)
                .findFirst()
                .orElseThrow();
        shop.set(diamond + ".worth", 123.45);
        shop.save(shopFile);
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.set("starting-balance", 5);
        config.save(configFile);

        assertTrue(run(admin(), "smp reload").contains("reloaded"));
        plugin = (SmpCore) loader.core();
        assertEquals(123_45, plugin.catalog().get(Material.DIAMOND).worthCents());
        assertEquals(5_00, plugin.settings().startingCents());
    }

    @Test
    void oldConfigsGetNewSettingsFilledIn() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        old.set("popular-stocks", null);
        old.save(file);
        loader.reload();
        plugin = (SmpCore) loader.core();
        assertTrue(plugin.settings().popularStocks().containsKey("AAPL"));
        assertTrue(YamlConfiguration.loadConfiguration(file).contains("popular-stocks"));
    }

    @Test
    void reloadIsAdminOnly() {
        PlayerMock p = player("steve");
        assertFalse(run(p, "smp reload").contains("reloaded"));
    }

    @Test
    void oldNetheriteMarketDataMovesOver() {
        PlayerMock p = player("steve");
        setBalance(p, 777_00);
        server.getPluginManager().disablePlugin(loader);
        File folder = loader.getDataFolder();
        assertTrue(folder.renameTo(new File(folder.getParentFile(), "NetheriteMarket")));

        server.getPluginManager().enablePlugin(loader);
        assertTrue(loader.isEnabled());
        plugin = (SmpCore) loader.core();
        assertEquals(777_00, balance(p));
    }

    @Test
    void hotSwapsCodeFromCoreJar() throws Exception {
        PlayerMock p = player("steve");
        int listeners = clickListeners();
        installCoreJar();
        PlayerMock admin = admin();
        assertTrue(run(admin, "smp reload").contains("reloaded"));
        assertTrue(run(admin, "smp reload").contains("reloaded"));

        var core = loader.core();
        assertNotEquals(SmpCore.class.getClassLoader(), core.getClass().getClassLoader(), "still running the old classes");
        assertEquals(listeners, clickListeners(), "old listeners weren't removed");
        assertTrue(run(p, "bal").contains("$100.00"), "money survives and commands work");
    }

    @Test
    void brokenCoreFallsBackToTheLastOne() throws Exception {
        PlayerMock p = player("steve");
        installCoreJar();
        PlayerMock admin = admin();
        run(admin, "smp reload");
        Files.writeString(new File(loader.getDataFolder(), "core/core.jar").toPath(), "not a jar");

        String out = run(admin, "smp reload");
        assertTrue(out.contains("Reload failed") && out.contains("Went back"), out);
        assertTrue(run(p, "bal").contains("$100.00"));
    }

    @Test
    void openMenusCloseOnReload() {
        PlayerMock p = player("steve");
        run(p, "shop");
        run(admin(), "smp reload");
        var top = p.getOpenInventory().getTopInventory();
        assertTrue(top == null || top.getHolder() == null || !top.getHolder().getClass().getName().contains("ShopMenu"));
    }

    @Test
    void tipsCycleInChat() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("tip-interval-minutes", 1);
        config.set("tips", java.util.List.of("first tip", "second tip"));
        config.save(file);
        loader.reload();
        PlayerMock p = player("steve");

        server.getScheduler().performTicks(60 * 20);
        assertTrue(saidAll(p).contains("first tip"));
        server.getScheduler().performTicks(60 * 20);
        assertTrue(saidAll(p).contains("second tip"));
        server.getScheduler().performTicks(60 * 20);
        assertTrue(saidAll(p).contains("first tip"));
    }
}
