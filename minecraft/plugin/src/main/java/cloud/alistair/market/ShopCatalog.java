package cloud.alistair.market;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Prices from shop.yml, plus storage blocks derived from their base item. */
public final class ShopCatalog {
    /** {@code worthCents} is for one of {@code material}; {@code base} is what supply is tracked under. */
    public record Item(Material material, Material base, int multiplier, long worthCents, boolean buyable, boolean sellable) {}

    public record Category(String key, String name, Material icon, List<Item> items) {}

    private record Pack(Material block, int multiplier) {}

    private static final Map<Material, Pack> PACKS = new EnumMap<>(Material.class);

    static {
        pack(Material.IRON_INGOT, Material.IRON_BLOCK, 9);
        pack(Material.GOLD_INGOT, Material.GOLD_BLOCK, 9);
        pack(Material.DIAMOND, Material.DIAMOND_BLOCK, 9);
        pack(Material.EMERALD, Material.EMERALD_BLOCK, 9);
        pack(Material.COAL, Material.COAL_BLOCK, 9);
        pack(Material.REDSTONE, Material.REDSTONE_BLOCK, 9);
        pack(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK, 9);
        pack(Material.COPPER_INGOT, Material.COPPER_BLOCK, 9);
        pack(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, 9);
        pack(Material.RAW_IRON, Material.RAW_IRON_BLOCK, 9);
        pack(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK, 9);
        pack(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK, 9);
        pack(Material.WHEAT, Material.HAY_BLOCK, 9);
        pack(Material.SLIME_BALL, Material.SLIME_BLOCK, 9);
        pack(Material.DRIED_KELP, Material.DRIED_KELP_BLOCK, 9);
        pack(Material.MELON_SLICE, Material.MELON, 9);
        pack(Material.NETHER_WART, Material.NETHER_WART_BLOCK, 9);
        pack(Material.QUARTZ, Material.QUARTZ_BLOCK, 4);
        pack(Material.AMETHYST_SHARD, Material.AMETHYST_BLOCK, 4);
        pack(Material.CLAY_BALL, Material.CLAY, 4);
        pack(Material.SNOWBALL, Material.SNOW_BLOCK, 4);
        pack(Material.GLOWSTONE_DUST, Material.GLOWSTONE, 4);
        pack(Material.HONEY_BOTTLE, Material.HONEY_BLOCK, 4);
        pack(Material.PRISMARINE_SHARD, Material.PRISMARINE, 4);
    }

    private static void pack(Material base, Material block, int n) {
        PACKS.put(base, new Pack(block, n));
    }

    private final List<Category> categories;
    private final Map<Material, Item> byMaterial;

    private ShopCatalog(List<Category> categories, Map<Material, Item> byMaterial) {
        this.categories = categories;
        this.byMaterial = byMaterial;
    }

    public static ShopCatalog load(File file, Logger log) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        List<Category> cats = new ArrayList<>();
        Map<Material, Item> all = new LinkedHashMap<>();
        ConfigurationSection root = yml.getConfigurationSection("categories");
        if (root == null) {
            log.warning("shop.yml has no categories");
            return new ShopCatalog(cats, all);
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(key);
            if (c == null) continue;
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            List<Item> items = new ArrayList<>();
            ConfigurationSection entries = c.getConfigurationSection("items");
            if (entries != null) {
                for (String name : entries.getKeys(false)) {
                    Material m = Material.matchMaterial(name);
                    if (m == null || !m.isItem()) {
                        log.warning("shop.yml: unknown item " + name + ", skipping");
                        continue;
                    }
                    long worth = Math.round(entries.getDouble(name + ".worth") * 100);
                    boolean buy = entries.getBoolean(name + ".buy", true);
                    boolean sell = entries.getBoolean(name + ".sell", c.getBoolean("sell", true));
                    if (worth <= 0) continue;
                    Item base = new Item(m, m, 1, worth, buy, sell);
                    items.add(base);
                    all.put(m, base);
                    Pack pack = PACKS.get(m);
                    if (pack != null) {
                        Item block = new Item(pack.block(), m, pack.multiplier(), worth * pack.multiplier(), buy, sell);
                        items.add(block);
                        all.put(pack.block(), block);
                    }
                }
            }
            cats.add(new Category(key, c.getString("name", key), icon == null ? Material.CHEST : icon, items));
        }
        return new ShopCatalog(cats, all);
    }

    public List<Category> categories() {
        return categories;
    }

    public Item get(Material m) {
        return byMaterial.get(m);
    }

    /** The item if the server will buy it from players, else null. */
    public Item sellable(Material m) {
        Item it = byMaterial.get(m);
        return it != null && it.sellable() ? it : null;
    }

    public int size() {
        return byMaterial.size();
    }
}
