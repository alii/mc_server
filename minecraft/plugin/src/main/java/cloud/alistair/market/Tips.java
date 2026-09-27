package cloud.alistair.market;

import java.util.List;

/** Every few minutes, the next tip from config.yml in chat. Skipped while nobody's online. */
final class Tips {
    private static final String PREFIX = "<dark_gray>[<gradient:#b86bff:#ff6bd6>tip</gradient>]</dark_gray> <gray>";

    private Tips() {}

    static void start(SmpCore core) {
        List<String> tips = core.settings().tips();
        int minutes = core.settings().tipIntervalMinutes();
        if (tips.isEmpty() || minutes <= 0) return;
        long ticks = minutes * 60L * 20L;
        int[] next = {0};
        // Tied to the host, so the loader cancels it on reload.
        core.getServer().getScheduler().runTaskTimer(core.host(), () -> {
            if (core.getServer().getOnlinePlayers().isEmpty()) return;
            core.getServer().broadcast(Msg.mm(PREFIX + tips.get(next[0] % tips.size())));
            next[0]++;
        }, ticks, ticks);
    }
}
