package cloud.alistair.smp.loader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * What Paper actually loads. It only owns the command list and {@code /smp reload}; everything
 * else lives in core.jar ({@code plugins/SmpPlugin/core/core.jar}), which reload swaps without a
 * server restart. If core.jar isn't there (tests), the core runs from this plugin's own classpath.
 */
public class SmpLoader extends JavaPlugin implements TabExecutor {
    private static final String ENTRY = "cloud.alistair.market.SmpCore";

    private Module module;
    private ChildFirstLoader classes;
    /** The copy of core.jar the running core was loaded from, kept to fall back to. */
    private File running;

    @Override
    public void onEnable() {
        if (!moveOldData()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        File[] stale = new File(getDataFolder(), ".running").listFiles();
        if (stale != null) for (File f : stale) f.delete();
        PluginCommand smp = getCommand("smp");
        smp.setExecutor(this);
        smp.setTabCompleter(this);
        try {
            load(coreJar());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Core didn't start. Fix it and run /smp reload", e);
        }
    }

    @Override
    public void onDisable() {
        unload();
    }

    /** The running core, for tests. */
    public Module core() {
        return module;
    }

    /** Swaps in the current core.jar. If it won't start, puts the previous one back. */
    public void reload() throws Exception {
        File previous = running;
        unload();
        try {
            load(coreJar());
        } catch (Exception e) {
            unload();
            if (previous != null && previous.exists()) {
                getLogger().log(Level.SEVERE, "New core failed, going back to the last one", e);
                load(previous);
            }
            throw e;
        }
        if (previous != null && !previous.equals(running)) Files.deleteIfExists(previous.toPath());
    }

    private File coreJar() {
        return new File(getDataFolder(), "core/core.jar");
    }

    private void load(File jar) throws Exception {
        Class<?> entry;
        if (jar.isFile()) {
            // Load from a private copy, so a build can overwrite core.jar while this one runs.
            File dir = new File(getDataFolder(), ".running");
            dir.mkdirs();
            File copy = jar.getParentFile().equals(dir) ? jar : new File(dir, "core-" + System.nanoTime() + ".jar");
            if (copy != jar) Files.copy(jar.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            classes = new ChildFirstLoader(copy.toURI().toURL(), getClassLoader());
            running = copy;
            entry = classes.loadClass(ENTRY);
        } else {
            running = null;
            entry = Class.forName(ENTRY);
        }
        Module m = (Module) entry.getConstructor().newInstance();
        m.enable(this);
        module = m;
    }

    /** Stops the core and removes everything it hooked into the server. */
    private void unload() {
        closeCoreMenus();
        if (module != null) {
            try {
                module.disable();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Core didn't stop cleanly", e);
            }
            module = null;
        }
        HandlerList.unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
        for (String name : getDescription().getCommands().keySet()) {
            PluginCommand c = getCommand(name);
            if (c == null || name.equals("smp")) continue;
            c.setExecutor((sender, cmd, label, args) -> {
                sender.sendMessage("SmpPlugin is reloading or broken. Try again in a moment.");
                return true;
            });
            c.setTabCompleter((sender, cmd, label, args) -> List.of());
        }
        if (classes != null) {
            try {
                classes.close();
            } catch (IOException ignored) {
            }
            classes = null;
        }
    }

    /** Menus hold the old core's objects, and clicks on them would run old code. */
    private void closeCoreMenus() {
        for (Player p : getServer().getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() != null
                    && top.getHolder().getClass().getName().startsWith(ChildFirstLoader.CORE_PACKAGE)) {
                p.closeInventory();
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("Usage: /smp reload");
            return true;
        }
        long start = System.nanoTime();
        try {
            reload();
            sender.sendMessage("SmpPlugin reloaded in " + (System.nanoTime() - start) / 1_000_000 + "ms"
                    + (running == null ? " (no core.jar, using the built-in copy)" : ""));
        } catch (Exception e) {
            sender.sendMessage("Reload failed: " + e + (module != null ? ". Went back to the last working version." : ""));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? List.of("reload") : List.of();
    }
}
