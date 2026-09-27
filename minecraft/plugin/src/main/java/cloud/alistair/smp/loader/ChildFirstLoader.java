package cloud.alistair.smp.loader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Loads the core's own classes and files from core.jar before asking the parent. Without this, a
 * stale copy on the parent's classpath (like in tests) would win and nothing would reload.
 */
final class ChildFirstLoader extends URLClassLoader {
    static final String CORE_PACKAGE = "cloud.alistair.market.";

    static {
        registerAsParallelCapable();
    }

    ChildFirstLoader(URL jar, ClassLoader parent) {
        super(new URL[] {jar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(CORE_PACKAGE)) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) c = findClass(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }
}
