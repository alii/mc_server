package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MarketTest extends PluginTest {
    private PlayerMock listSword(String seller, String price) {
        PlayerMock s = player(seller);
        s.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        run(s, "market sell " + price);
        return s;
    }

    @Test
    void listingTakesTheItem() {
        PlayerMock s = listSword("steve", "50");
        assertEquals(0, count(s, Material.DIAMOND_SWORD));
        assertEquals(1, plugin.db().listings(null).size());
    }

    @Test
    void buyingMovesItemAndMoneyMinusFee() {
        PlayerMock s = listSword("steve", "50");
        PlayerMock b = player("alex");
        run(b, "market");
        click(b, slotOf(b, Material.DIAMOND_SWORD), ClickType.LEFT);
        assertEquals(1, count(b, Material.DIAMOND_SWORD));
        assertEquals(50_00, balance(b));
        assertEquals(100_00 + 49_00, balance(s)); // 2% fee
        assertEquals(0, plugin.db().listings(null).size());
        assertTrue(saidAll(s).contains("alex bought your"));
    }

    @Test
    void cantAffordKeepsTheListing() {
        listSword("steve", "500");
        PlayerMock b = player("alex");
        run(b, "market");
        click(b, slotOf(b, Material.DIAMOND_SWORD), ClickType.LEFT);
        assertEquals(0, count(b, Material.DIAMOND_SWORD));
        assertEquals(100_00, balance(b));
        assertEquals(1, plugin.db().listings(null).size());
    }

    @Test
    void sellerCanTakeItBack() {
        PlayerMock s = listSword("steve", "50");
        run(s, "market mine");
        click(s, slotOf(s, Material.DIAMOND_SWORD), ClickType.LEFT);
        assertEquals(1, count(s, Material.DIAMOND_SWORD));
        assertEquals(100_00, balance(s));
        assertEquals(0, plugin.db().listings(null).size());
    }

    @Test
    void twoBuyersCantBothGetIt() {
        listSword("steve", "50");
        PlayerMock a = player("alex"), b = player("sam");
        run(a, "market");
        run(b, "market");
        int slot = slotOf(a, Material.DIAMOND_SWORD);
        click(a, slot, ClickType.LEFT);
        said(b);
        // sam's menu is stale now; the click must not sell the sword twice.
        b.simulateInventoryClick(b.getOpenInventory(), ClickType.LEFT, slot);
        assertEquals(1, count(a, Material.DIAMOND_SWORD) + count(b, Material.DIAMOND_SWORD));
        assertEquals(100_00, balance(b));
    }

    @Test
    void listingLimit() {
        PlayerMock s = player("steve");
        for (int i = 0; i < 27; i++) {
            s.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
            run(s, "market sell 1");
        }
        s.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
        assertTrue(run(s, "market sell 1").contains("already have 27"));
        assertEquals(1, count(s, Material.DIRT));
    }

    @Test
    void emptyHandOrBadPrice() {
        PlayerMock s = player("steve");
        assertTrue(run(s, "market sell 5").contains("Hold the item"));
        s.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
        assertTrue(run(s, "market sell -5").contains("not a price"));
        assertEquals(1, count(s, Material.DIRT));
    }

    @Test
    void menuClicksNeverMoveItems() {
        listSword("steve", "50");
        PlayerMock b = player("alex");
        run(b, "market");
        assertTrue(click(b, 49, ClickType.LEFT).isCancelled());
    }
}
