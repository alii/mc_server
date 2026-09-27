package cloud.alistair.smp.loader;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The hot-swappable part of the plugin. Lives in core.jar and gets a fresh class loader on every
 * {@code /smp reload}, so anything it registers must be tied to {@code host} (listeners, tasks,
 * command executors). The loader cleans those up.
 */
public interface Module {
    /** Throws if it can't start; the loader then goes back to the last working core. */
    void enable(JavaPlugin host) throws Exception;

    void disable();
}
