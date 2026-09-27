package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ShopTest extends PluginTest {
    // saturation-dollars is $2,000, so the first diamond pays 200000 × ln(204000/200000) cents.
    private static final long FIRST_DIAMOND = 3960;

    @Test
    void newPlayersGetStartingBalanceOnce() {
        PlayerMock p = server.addPlayer("steve");
        assertEquals(100_00, balance(p));
        p.disconnect();
        p.reconnect();
        assertEquals(100_00, balance(p));
    }

    @Test
    void sellHandPaysAndEmptiesHand() {
        PlayerMock p = player("steve");
        p.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));
        assertTrue(run(p, "sell hand").contains("Sold 1× Diamond"));
        assertEquals(100_00 + FIRST_DIAMOND, balance(p));
        assertTrue(p.getInventory().getItemInMainHand().getType().isAir());
    }

    @Test
    void sellHandRefusesRenamedItems() {
        PlayerMock p = player("steve");
        ItemStack named = new ItemStack(Material.DIAMOND);
        named.editMeta(m -> m.displayName(Component.text("Lucky")));
        p.getInventory().setItemInMainHand(named);
        assertTrue(run(p, "sell hand").contains("doesn't buy that"));
        assertEquals(100_00, balance(p));
        assertEquals(1, count(p, Material.DIAMOND));
    }

    @Test
    void sellAllOnlyPreviewsAtFirst() {
        PlayerMock p = player("steve");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 10), new ItemStack(Material.COBBLESTONE, 64));
        String out = run(p, "sell all");
        assertTrue(out.contains("would sell"), out);
        assertTrue(out.contains("[Confirm]"), out);
        assertTrue(out.contains("can't buy back"), "diamonds should be flagged: " + out);
        assertEquals(10, count(p, Material.DIAMOND));
        assertEquals(64, count(p, Material.COBBLESTONE));
        assertEquals(100_00, balance(p));
    }

    @Test
    void confirmWithoutPreviewOnlyPreviews() {
        PlayerMock p = player("steve");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));
        assertTrue(run(p, "sell all confirm").contains("would sell"));
        assertEquals(10, count(p, Material.DIAMOND));
    }

    @Test
    void sellAllConfirmSellsShopItemsOnly() {
        PlayerMock p = player("steve");
        ItemStack named = new ItemStack(Material.DIAMOND);
        named.editMeta(m -> m.displayName(Component.text("Lucky")));
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 10), new ItemStack(Material.COBBLESTONE, 64),
                new ItemStack(Material.DIAMOND_SWORD), named);
        run(p, "sell all");
        assertTrue(run(p, "sell all confirm").contains("Sold 74 items"));
        assertEquals(1, count(p, Material.DIAMOND), "renamed diamond is kept");
        assertEquals(0, count(p, Material.COBBLESTONE));
        assertEquals(1, count(p, Material.DIAMOND_SWORD));
        assertTrue(balance(p) > 100_00 + 30_000);
    }

    @Test
    void confirmIsSingleUse() {
        PlayerMock p = player("steve");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 1));
        run(p, "sell all");
        run(p, "sell all confirm");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        assertTrue(run(p, "sell all confirm").contains("would sell"));
        assertEquals(5, count(p, Material.DIAMOND));
    }

    @Test
    void floodingAnItemHalvesItsPrice() {
        PlayerMock p = player("steve");
        // $2,000 of iron at $4 each.
        for (int i = 0; i < 500 / 50; i++) {
            p.getInventory().setItemInMainHand(new ItemStack(Material.IRON_INGOT, 50));
            run(p, "sell hand");
        }
        long before = balance(p);
        p.getInventory().setItemInMainHand(new ItemStack(Material.IRON_INGOT, 1));
        run(p, "sell hand");
        long one = balance(p) - before;
        assertTrue(one >= 198 && one <= 200, "one ingot paid " + one);
    }

    @Test
    void sellingInOneGoPaysTheSameAsOneAtATime() {
        PlayerMock p = player("steve");
        p.getInventory().setItemInMainHand(new ItemStack(Material.IRON_INGOT, 64));
        run(p, "sell hand");
        long batch = balance(p) - 100_00;
        double e = 200_000, v = 64 * 400;
        assertEquals((long) Math.floor(e * Math.log((e + v) / e)), batch);

        PlayerMock q = player("alex");
        q.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 64));
        long start = balance(q);
        run(q, "shop");
        click(q, slotOf(q, Material.DIAMOND), ClickType.LEFT); // Ores category icon
        int gold = slotOf(q, Material.GOLD_INGOT);
        for (int i = 0; i < 64; i++) click(q, gold, ClickType.LEFT);
        long oneByOne = balance(q) - start;
        double gv = 64 * 600;
        long expected = (long) Math.floor(e * Math.log((e + gv) / e));
        assertTrue(Math.abs(oneByOne - expected) <= 64, "one-by-one " + oneByOne + " vs batch " + expected);
        assertEquals(0, count(q, Material.GOLD_INGOT));
    }

    @Test
    void storageBlocksShareTheirItemsPrice() {
        PlayerMock p = player("steve");
        p.getInventory().setItemInMainHand(new ItemStack(Material.IRON_BLOCK, 56)); // 504 ingots
        run(p, "sell hand");
        long before = balance(p);
        p.getInventory().setItemInMainHand(new ItemStack(Material.IRON_INGOT, 1));
        run(p, "sell hand");
        assertTrue(balance(p) - before < 210, "iron should be cheap after selling blocks");
    }

    @Test
    void buyingCostsThreeTimesSellPrice() {
        PlayerMock p = player("steve");
        run(p, "shop");
        click(p, slotOf(p, Material.WHEAT), ClickType.LEFT); // Farming category icon
        click(p, slotOf(p, Material.WHEAT), ClickType.RIGHT);
        assertEquals(100_00 - 30, balance(p));
        assertEquals(1, count(p, Material.WHEAT));
    }

    @Test
    void shiftRightBuysAStack() {
        PlayerMock p = player("steve");
        run(p, "shop");
        click(p, slotOf(p, Material.OAK_LOG), ClickType.LEFT);
        click(p, slotOf(p, Material.OAK_LOG), ClickType.SHIFT_RIGHT);
        assertEquals(64, count(p, Material.OAK_LOG));
        assertEquals(100_00 - 64 * 120, balance(p));
    }

    @Test
    void rareThingsCantBeBought() {
        PlayerMock p = player("steve");
        setBalance(p, 1_000_000_00);
        run(p, "shop");
        click(p, slotOf(p, Material.DIAMOND), ClickType.LEFT);
        said(p);
        click(p, slotOf(p, Material.DIAMOND), ClickType.RIGHT);
        assertTrue(saidAll(p).contains("doesn't sell that"));
        assertEquals(0, count(p, Material.DIAMOND));
        assertEquals(1_000_000_00, balance(p));
    }

    @Test
    void cantBuyWithoutTheMoney() {
        PlayerMock p = player("steve");
        setBalance(p, 10);
        run(p, "shop");
        click(p, slotOf(p, Material.OAK_LOG), ClickType.LEFT);
        click(p, slotOf(p, Material.OAK_LOG), ClickType.RIGHT);
        assertEquals(10, balance(p));
        assertEquals(0, count(p, Material.OAK_LOG));
    }

    @Test
    void buyingThenSellingBackAlwaysLoses() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        run(p, "shop");
        click(p, slotOf(p, Material.DIAMOND), ClickType.LEFT);
        int iron = slotOf(p, Material.IRON_INGOT);
        click(p, iron, ClickType.SHIFT_RIGHT);
        assertEquals(64, count(p, Material.IRON_INGOT));
        click(p, iron, ClickType.SHIFT_LEFT);
        assertEquals(0, count(p, Material.IRON_INGOT));
        assertTrue(balance(p) < 1000_00 - 500_00, "lost only " + (1000_00 - balance(p)));
    }

    @Test
    void menuClicksNeverMoveItems() {
        PlayerMock p = player("steve");
        run(p, "shop");
        var e = click(p, 10, ClickType.LEFT);
        assertTrue(e.isCancelled());
        e = click(p, slotOf(p, Material.COAL), ClickType.SHIFT_LEFT);
        assertTrue(e.isCancelled());
        assertEquals(0, count(p, Material.COAL));
    }

    @Test
    void decorIsBuyOnly() {
        PlayerMock p = player("steve");
        run(p, "shop");
        click(p, slotOf(p, Material.GLASS), ClickType.LEFT); // Glass category icon
        click(p, slotOf(p, Material.GLASS), ClickType.SHIFT_RIGHT);
        assertEquals(64, count(p, Material.GLASS));
        assertEquals(100_00 - 64 * 30, balance(p));
        long after = balance(p);
        click(p, slotOf(p, Material.GLASS), ClickType.SHIFT_LEFT);
        assertEquals(64, count(p, Material.GLASS), "glass can't be sold back");
        assertEquals(after, balance(p));
        p.getInventory().setItemInMainHand(new ItemStack(Material.GLASS, 64));
        assertTrue(run(p, "sell hand").contains("doesn't buy that"));
        assertTrue(run(p, "sell all").contains("Nothing in your inventory"));
    }

    @Test
    void everyShopItemIsARealItem() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.logging.Logger log = java.util.logging.Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { warnings.add(r.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        ShopCatalog c = ShopCatalog.load(new java.io.File(plugin.getDataFolder(), "shop.yml"), log);
        assertEquals(java.util.List.of(), warnings);
        assertTrue(c.categories().size() <= 12, "category menu has 12 slots");
        for (var cat : c.categories()) assertTrue(cat.items().size() <= 45, cat.name() + " has more than one page");
    }

    @Test
    void cookedFoodIsBuyOnlyButRawMeatSells() {
        PlayerMock p = player("steve");
        p.getInventory().addItem(new ItemStack(Material.BEEF, 10), new ItemStack(Material.COOKED_BEEF, 10));
        run(p, "sell all");
        run(p, "sell all confirm");
        assertEquals(0, count(p, Material.BEEF));
        assertEquals(10, count(p, Material.COOKED_BEEF));
        run(p, "shop");
        click(p, slotOf(p, Material.COOKED_BEEF), ClickType.LEFT); // Food category icon
        long before = balance(p);
        click(p, slotOf(p, Material.BREAD), ClickType.RIGHT);
        assertEquals(before - 1_05, balance(p));
    }
}
