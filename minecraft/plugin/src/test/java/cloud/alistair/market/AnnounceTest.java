package cloud.alistair.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AnnounceTest extends PluginTest {
    private static final int FEED_TICKS = 10 * 20;

    /** MockBukkit can't sleep, so this player just says it is. */
    private static final class Sleeper extends PlayerMock {
        boolean asleep;

        Sleeper(org.mockbukkit.mockbukkit.ServerMock server, String name) {
            super(server, name, java.util.UUID.nameUUIDFromBytes(name.getBytes()));
        }

        @Override
        public boolean isSleeping() {
            return asleep;
        }
    }

    private Sleeper sleeper(String name) {
        Sleeper s = new Sleeper(server, name);
        server.addPlayer(s);
        said(s);
        return s;
    }

    private static final io.papermc.paper.block.bed.BedEnterAction ALLOWED = new io.papermc.paper.block.bed.BedEnterAction() {
        public io.papermc.paper.block.bed.BedRuleResult canSleep() {
            return io.papermc.paper.block.bed.BedRuleResult.ALLOWED;
        }

        public io.papermc.paper.block.bed.BedRuleResult canSetSpawn() {
            return io.papermc.paper.block.bed.BedRuleResult.ALLOWED;
        }

        public io.papermc.paper.block.bed.BedEnterProblem problem() {
            return null;
        }

        public net.kyori.adventure.text.Component errorMessage() {
            return null;
        }
    };

    /** What the server does when someone lies down: they're asleep from the next tick. */
    private void goToBed(Sleeper s) {
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerBedEnterEvent(
                s, s.getLocation().getBlock(), org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.OK, ALLOWED));
        s.asleep = true;
        server.getScheduler().performTicks(1);
    }

    private long lines(String text, String needle) {
        return text.lines().filter(l -> l.contains(needle)).count();
    }

    @Test
    void manyClicksBecomeOneLine() {
        PlayerMock seller = player("steve");
        PlayerMock watcher = player("alex");
        seller.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        run(seller, "shop");
        click(seller, slotOf(seller, Material.DIAMOND), ClickType.LEFT); // Ores category
        for (int i = 0; i < 5; i++) click(seller, slotOf(seller, Material.DIAMOND), ClickType.LEFT);

        assertFalse(saidAll(watcher).contains("sold"), "posted before the batch closed");
        server.getScheduler().performTicks(FEED_TICKS);
        String chat = saidAll(watcher);
        assertEquals(1, lines(chat, "steve sold"), chat);
        assertTrue(chat.contains("5× Diamond"), chat);
    }

    @Test
    void sellAllAndBuysArePosted() {
        PlayerMock p = player("steve");
        setBalance(p, 1000_00);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 10), new ItemStack(Material.IRON_INGOT, 64));
        run(p, "sell all");
        click(p, ShopMenu.SELLBOX_CONFIRM, ClickType.LEFT);
        run(p, "shop");
        click(p, slotOf(p, Material.COOKED_BEEF), ClickType.LEFT); // Food category
        click(p, slotOf(p, Material.BREAD), ClickType.SHIFT_RIGHT);
        said(p);

        server.getScheduler().performTicks(FEED_TICKS);
        String chat = saidAll(p);
        assertTrue(chat.contains("steve sold 10× Diamond, 64× Iron ingot for $"), chat);
        assertTrue(chat.contains("steve bought 64× Bread for $"), chat);
    }

    @Test
    void smallTradesStayQuiet() {
        PlayerMock p = player("steve");
        p.getInventory().setItemInMainHand(new ItemStack(Material.COBBLESTONE, 1));
        run(p, "sell hand");
        server.getScheduler().performTicks(FEED_TICKS);
        assertFalse(saidAll(p).contains("steve sold"));
    }

    @Test
    void sleepingSaysHowManyMoreAreNeeded() {
        Sleeper a = sleeper("steve");
        Sleeper b = sleeper("alex");
        sleeper("sam");
        a.getWorld().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
        goToBed(a);
        String chat = saidAll(b);
        assertTrue(chat.contains("steve is sleeping. 2 more players need to sleep"), chat);
        assertTrue(chat.contains("(1/3)"), chat);
    }

    @Test
    void secondSleeperUpdatesTheCount() {
        Sleeper a = sleeper("steve");
        Sleeper b = sleeper("alex");
        Sleeper c = sleeper("sam");
        a.getWorld().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 100);
        goToBed(a);
        goToBed(b);
        assertTrue(saidAll(c).contains("alex is sleeping. 1 more player needs to sleep"));
    }

    @Test
    void noNoticeWhenThatWasEnough() {
        Sleeper a = sleeper("steve");
        Sleeper b = sleeper("alex");
        a.getWorld().setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, 30);
        goToBed(a);
        assertFalse(saidAll(b).contains("is sleeping"));
    }
}
