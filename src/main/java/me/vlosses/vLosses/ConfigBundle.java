package me.vlosses.vLosses;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ConfigBundle(FileConfiguration main, FileConfiguration displays, FileConfiguration items,
                           Map<String, FileConfiguration> languages) {
    public static final List<String> LANGUAGES = List.of("tr", "en", "de", "es", "fr", "pt");

    public static ConfigBundle load(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        FileConfiguration main = read(plugin, "config.yml");
        FileConfiguration displays = read(plugin, "displays.yml");
        FileConfiguration items = read(plugin, "items.yml");
        Map<String, FileConfiguration> languages = new LinkedHashMap<>();
        for (String language : LANGUAGES) languages.put(language, read(plugin, "languages/" + language + ".yml"));
        validate(main, displays, items);
        return new ConfigBundle(main, displays, items, Map.copyOf(languages));
    }

    private static FileConfiguration read(JavaPlugin plugin, String name) throws IOException, InvalidConfigurationException {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.isFile()) plugin.saveResource(name, false);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    public static void validate(FileConfiguration main, FileConfiguration displays, FileConfiguration items) {
        Object rawWorlds = main.get("lobby.worlds");
        if (!(rawWorlds instanceof List<?> list) || list.isEmpty()
                || list.stream().anyMatch(value -> !(value instanceof String name) || name.isBlank())) invalid("config.yml: lobby.worlds");
        if (!LANGUAGES.contains(main.getString("language.default", "tr"))) invalid("config.yml: language.default");
        integer(displays, "tab.interval-ticks", 20, 2, 72000);
        integer(displays, "scoreboard.interval-ticks", 20, 2, 72000);
        integer(displays, "announcements.interval-seconds", 120, 10, 86400);
        integer(main, "bridge.poll-interval-ticks", 100, 100, 72000);
        integer(items, "cooldown-ms", 750, 100, 60000);
        integer(main, "lobby.time.ticks", 6000, 0, 23999);
        if (!(main.get("lobby.health.value", 20.0) instanceof Number)) invalid("lobby.health.value");
        double health = main.getDouble("lobby.health.value", 20);
        if (!Double.isFinite(health) || health <= 0 || health > 2048) invalid("lobby.health.value: 0 < value <= 2048");
        if (displays.getStringList("scoreboard.lines").size() > 15) invalid("scoreboard.lines: <= 15");
        for (String key : List.of("x", "y", "z", "yaw", "pitch")) {
            if (main.contains("lobby.spawn." + key) && (!main.isDouble("lobby.spawn." + key) && !main.isInt("lobby.spawn." + key)
                    && !(main.get("lobby.spawn." + key) instanceof Number))) invalid("lobby.spawn." + key);
            if (!Double.isFinite(main.getDouble("lobby.spawn." + key))) invalid("lobby.spawn." + key);
        }
        var animations = displays.getConfigurationSection("animations");
        if (animations != null) for (String name : animations.getKeys(false)) {
            integer(animations, name + ".interval-ticks", 20, 2, 72000);
            if (animations.getStringList(name + ".frames").isEmpty()) invalid("animations." + name + ".frames");
        }
        var definitions = items.getConfigurationSection("items");
        if (definitions != null) {
            java.util.Set<Integer> slots = new java.util.HashSet<>();
            for (String id : definitions.getKeys(false)) {
                if (!definitions.contains(id + ".slot")) invalid("items." + id + ".slot");
                integer(definitions, id + ".slot", 0, 0, 8);
                if (!slots.add(definitions.getInt(id + ".slot"))) invalid("items." + id + ".slot: duplicate");
            }
        }
    }

    private static void integer(org.bukkit.configuration.ConfigurationSection section, String key, int fallback, int min, int max) {
        Object raw = section.get(key, fallback);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()
                || number.longValue() < min || number.longValue() > max) invalid(key + ": " + min + ".." + max);
    }

    private static void invalid(String message) {
        throw new IllegalArgumentException(message);
    }
}
