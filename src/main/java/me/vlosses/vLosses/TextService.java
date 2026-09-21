package me.vlosses.vLosses;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextService implements AutoCloseable {
    private static final Pattern LANGUAGE = Pattern.compile("\\{lang:([a-zA-Z0-9_.-]+)}");
    private static final Pattern VARIABLES = Pattern.compile("\\{(player|display_name|world|online|max_players|ping|prefix|suffix|group|server|network_online)}|%[^%\\s]{1,160}%");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private final VLosses plugin;
    private final Map<RenderKey, Component> renders = boundedMap(2_048);
    private final Map<UUID, Metadata> metadata = boundedMap(2_048);
    private final Map<String, Long> warnings = new LinkedHashMap<>();
    private int metadataTick = -1;

    public TextService(VLosses plugin) {
        this.plugin = plugin;
    }

    public Component render(Player player, String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        String template = language(player, input);
        Matcher matcher = VARIABLES.matcher(template);
        StringBuilder prepared = new StringBuilder(template.length());
        List<Component> values = new ArrayList<>();
        TagResolver.Builder resolvers = TagResolver.builder();
        Map<String, String> seen = new LinkedHashMap<>();
        while (matcher.find()) {
            String token = matcher.group();
            String tag = seen.get(token);
            if (tag == null) {
                Component value = matcher.group(1) == null ? external(player, token) : builtin(player, matcher.group(1));
                tag = "vlosses_value_" + values.size();
                seen.put(token, tag);
                values.add(value);
                resolvers.resolver(Placeholder.component(tag, value));
            }
            matcher.appendReplacement(prepared, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(prepared);
        RenderKey key = new RenderKey(prepared.toString(), List.copyOf(values));
        Component cached = renders.get(key);
        if (cached != null) return cached;
        Component result;
        try {
            result = MINI.deserialize(legacyToMiniMessage(key.template), resolvers.build());
        } catch (RuntimeException exception) {
            warn("format", "A display or message contains invalid formatting: " + exception.getClass().getSimpleName());
            result = Component.text(template);
        }
        renders.put(key, result);
        return result;
    }

    private String language(Player player, String input) {
        String result = input;
        for (int depth = 0; depth < 4; depth++) {
            Matcher matcher = LANGUAGE.matcher(result);
            if (!matcher.find()) break;
            StringBuilder replacement = new StringBuilder();
            do {
                String value = plugin.rawMessage(player, matcher.group(1));
                matcher.appendReplacement(replacement, Matcher.quoteReplacement(value == null ? matcher.group(1) : value));
            } while (matcher.find());
            matcher.appendTail(replacement);
            String updated = replacement.toString();
            if (updated.equals(result)) break;
            result = updated;
        }
        return result;
    }

    private Component builtin(Player player, String token) {
        return switch (token) {
            case "player" -> Component.text(player == null ? "Console" : player.getName());
            case "display_name" -> player == null ? Component.text("Console") : player.displayName();
            case "world" -> Component.text(player == null ? "" : player.getWorld().getName());
            case "online" -> Component.text(Bukkit.getOnlinePlayers().size());
            case "max_players" -> Component.text(Bukkit.getMaxPlayers());
            case "ping" -> Component.text(player == null ? 0 : Math.max(0, player.getPing()));
            case "prefix" -> legacyValue(meta(player).prefix);
            case "suffix" -> legacyValue(meta(player).suffix);
            case "group" -> Component.text(meta(player).group);
            case "server" -> Component.text(plugin.getConfig().getString("server-name", "VLosses"));
            case "network_online" -> Component.text(plugin.networkOnline());
            default -> Component.text("{" + token + "}");
        };
    }

    private Metadata meta(Player player) {
        if (player == null || !plugin.getConfig().getBoolean("integrations.luckperms", true)
                || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return Metadata.EMPTY;
        int tick = Bukkit.getCurrentTick();
        if (metadataTick != tick) {
            metadata.clear();
            metadataTick = tick;
        }
        Metadata cached = metadata.get(player.getUniqueId());
        if (cached != null) return cached;
        Metadata result = Metadata.EMPTY;
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                CachedMetaData data = LuckPermsProvider.get().getPlayerAdapter(Player.class).getMetaData(player);
                result = new Metadata(orEmpty(data.getPrefix()), orEmpty(data.getSuffix()), user.getPrimaryGroup());
            }
        } catch (RuntimeException | LinkageError exception) {
            warn("luckperms", "LuckPerms metadata is unavailable: " + exception.getClass().getSimpleName());
        }
        metadata.put(player.getUniqueId(), result);
        return result;
    }

    private Component external(Player player, String token) {
        if (player == null || !plugin.getConfig().getBoolean("integrations.placeholderapi", true)
                || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return Component.text(token);
        try {
            return legacyValue(PlaceholderAPI.setPlaceholders(player, token));
        } catch (RuntimeException | LinkageError exception) {
            warn("placeholderapi", "A PlaceholderAPI expansion failed: " + exception.getClass().getSimpleName());
            return Component.text(token);
        }
    }

    private void warn(String key, String text) {
        long now = System.nanoTime();
        Long previous = warnings.get(key);
        if (previous != null && now - previous < 60_000_000_000L) return;
        warnings.put(key, now);
        plugin.warn(text);
    }

    static Component legacyValue(String text) {
        return LEGACY.deserialize(orEmpty(text).replace('\u00a7', '&'));
    }

    static String legacyToMiniMessage(String input) {
        StringBuilder result = new StringBuilder(input.length());
        boolean insideTag = false;
        char quote = 0;
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '<' && !insideTag) insideTag = true;
            if (insideTag) {
                result.append(current);
                if ((current == '\'' || current == '"') && (index == 0 || input.charAt(index - 1) != '\\')) {
                    if (quote == 0) quote = current;
                    else if (quote == current) quote = 0;
                }
                if (current == '>' && quote == 0) insideTag = false;
                continue;
            }
            if ((current != '&' && current != '\u00a7') || index + 1 >= input.length()) {
                result.append(current);
                continue;
            }
            char code = Character.toLowerCase(input.charAt(index + 1));
            if (code == '#' && index + 7 < input.length()) {
                String hex = input.substring(index + 2, index + 8);
                if (hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                    result.append("<reset><#").append(hex).append('>');
                    index += 7;
                    continue;
                }
            }
            if (code == 'x' && index + 13 < input.length()) {
                StringBuilder hex = new StringBuilder(6);
                for (int offset = 2; offset <= 12; offset += 2) {
                    char marker = input.charAt(index + offset);
                    char digit = input.charAt(index + offset + 1);
                    if ((marker != '&' && marker != '\u00a7') || Character.digit(digit, 16) < 0) break;
                    hex.append(digit);
                }
                if (hex.length() == 6) {
                    result.append("<reset><#").append(hex).append('>');
                    index += 13;
                    continue;
                }
            }
            int color = Character.digit(code, 16);
            String tag = color >= 0 ? "reset><" + COLORS[color] : switch (code) {
                case 'k' -> "obfuscated";
                case 'l' -> "bold";
                case 'm' -> "strikethrough";
                case 'n' -> "underlined";
                case 'o' -> "italic";
                case 'r' -> "reset";
                default -> null;
            };
            if (tag == null) result.append(current);
            else {
                result.append('<').append(tag).append('>');
                index++;
            }
        }
        return result.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <K, V> Map<K, V> boundedMap(int maximum) {
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maximum;
            }
        };
    }

    public int cacheSize() {
        return renders.size();
    }

    @Override
    public void close() {
        renders.clear();
        metadata.clear();
        warnings.clear();
    }

    private record RenderKey(String template, List<Component> values) {}

    private record Metadata(String prefix, String suffix, String group) {
        private static final Metadata EMPTY = new Metadata("", "", "default");
    }
}
