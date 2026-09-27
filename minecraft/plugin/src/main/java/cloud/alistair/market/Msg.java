package cloud.alistair.market;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** MiniMessage helpers. Anything a player or Yahoo controls goes in as an unparsed placeholder. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gradient:#b86bff:#ff6bd6>market</gradient>]</dark_gray> ";

    private Msg() {}

    public static Component mm(String template, TagResolver... tags) {
        return MM.deserialize(template, tags);
    }

    public static TagResolver v(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    public static void ok(CommandSender to, String template, TagResolver... tags) {
        to.sendMessage(mm(PREFIX + "<gray>" + template, tags));
    }

    public static void err(CommandSender to, String template, TagResolver... tags) {
        to.sendMessage(mm(PREFIX + "<red>" + template, tags));
    }
}
