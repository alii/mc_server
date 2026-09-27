package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ReloadTest extends PluginTest {
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
        plugin.getConfig().set("starting-balance", 5);
        plugin.saveConfig();

        PlayerMock admin = player("admin");
        admin.setOp(true);
        assertTrue(run(admin, "smp reload").contains("Reloaded"));
        assertEquals(123_45, plugin.catalog().get(Material.DIAMOND).worthCents());
        assertEquals(5_00, plugin.settings().startingCents());
    }

    @Test
    void oldConfigsGetNewSettingsFilledIn() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        old.set("popular-stocks", null);
        old.save(file);
        plugin.reload();
        assertTrue(plugin.settings().popularStocks().containsKey("AAPL"));
        assertTrue(YamlConfiguration.loadConfiguration(file).contains("popular-stocks"));
    }

    @Test
    void reloadIsAdminOnly() {
        PlayerMock p = player("steve");
        assertFalse(run(p, "smp reload").contains("Reloaded"));
    }

    @Test
    void oldNetheriteMarketDataMovesOver() {
        PlayerMock p = player("steve");
        setBalance(p, 777_00);
        server.getPluginManager().disablePlugin(plugin);
        File folder = plugin.getDataFolder();
        assertTrue(folder.renameTo(new File(folder.getParentFile(), "NetheriteMarket")));

        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled());
        assertEquals(777_00, balance(p));
    }
}
