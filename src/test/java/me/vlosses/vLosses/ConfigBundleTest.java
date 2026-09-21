package me.vlosses.vLosses;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigBundleTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_-]*)}");

    @Test
    void bundledConfigurationLoadsAndPassesValidation() throws Exception {
        Fixtures defaults = defaults();
        assertDoesNotThrow(defaults::validate);
        assertFalse(defaults.main().getBoolean("bridge.enabled"));
        assertFalse(defaults.main().getBoolean("bridge.sync-language"));
        assertEquals("", defaults.main().getString("bridge.secret"));
    }

    @Test
    void everyLanguageContainsAllMessagesAndPreservesTheirPlaceholders() throws Exception {
        FileConfiguration english = resource("languages/en.yml");
        Set<String> keys = english.getKeys(true);
        assertFalse(keys.isEmpty());
        for (String language : ConfigBundle.LANGUAGES) {
            FileConfiguration localized = resource("languages/" + language + ".yml");
            assertEquals(keys, localized.getKeys(true), language + " message keys differ from English");
            for (String key : keys) {
                if (!english.isString(key)) continue;
                String translated = localized.getString(key);
                assertNotNull(translated, language + ": " + key);
                assertFalse(translated.isBlank(), language + ": " + key);
                assertEquals(placeholders(english.getString(key)), placeholders(translated),
                        language + ": " + key + " placeholder mismatch");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidValues")
    void invalidValuesAreRejectedBeforeReplacingRunningSettings(InvalidValue invalid) throws Exception {
        Fixtures values = defaults();
        FileConfiguration target = switch (invalid.file()) {
            case "main" -> values.main();
            case "displays" -> values.displays();
            default -> values.items();
        };
        target.set(invalid.path(), invalid.value());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, values::validate);
        assertTrue(error.getMessage().contains(invalid.path()), error.getMessage());
    }

    @Test
    void duplicateHotbarSlotsAreRejected() throws Exception {
        Fixtures values = defaults();
        values.items().set("items", null);
        values.items().set("items.discord.slot", 2);
        values.items().set("items.website.slot", 2);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, values::validate);
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void missingItemSlotIsRejectedInsteadOfSilentlyDisablingTheItem() throws Exception {
        Fixtures values = defaults();
        values.items().set("items", null);
        values.items().set("items.discord.material", "COMPASS");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, values::validate);
        assertTrue(error.getMessage().contains("slot"));
    }

    @Test
    void scoreboardCannotExceedMinecraftLineLimit() throws Exception {
        Fixtures values = defaults();
        values.displays().set("scoreboard.lines", Collections.nCopies(16, "line"));
        assertThrows(IllegalArgumentException.class, values::validate);
        values.displays().set("scoreboard.lines", Collections.nCopies(15, "line"));
        assertDoesNotThrow(values::validate);
    }

    @Test
    void animationMustHaveAtLeastOneFrame() throws Exception {
        Fixtures values = defaults();
        values.displays().set("animations.test.interval-ticks", 20);
        values.displays().set("animations.test.frames", List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, values::validate);
        assertTrue(error.getMessage().contains("frames"));
    }

    @Test
    void numericRangeEndpointsAreValid() throws Exception {
        Fixtures values = defaults();
        values.main().set("bridge.poll-interval-ticks", 100);
        values.main().set("lobby.time.ticks", 0);
        values.displays().set("tab.interval-ticks", 2);
        values.displays().set("scoreboard.interval-ticks", 72000);
        values.items().set("cooldown-ms", 60000);
        assertDoesNotThrow(values::validate);
        values.main().set("bridge.poll-interval-ticks", 72000);
        values.main().set("lobby.time.ticks", 23999);
        assertDoesNotThrow(values::validate);
    }

    private static Stream<InvalidValue> invalidValues() {
        return Stream.of(
                new InvalidValue("main", "lobby.worlds", List.of()),
                new InvalidValue("main", "lobby.worlds", List.of(" ")),
                new InvalidValue("main", "lobby.worlds", List.of(123)),
                new InvalidValue("main", "language.default", "unknown"),
                new InvalidValue("main", "bridge.poll-interval-ticks", 99),
                new InvalidValue("main", "bridge.poll-interval-ticks", 72001),
                new InvalidValue("main", "lobby.time.ticks", -1),
                new InvalidValue("main", "lobby.time.ticks", 24000),
                new InvalidValue("main", "lobby.health.value", 0),
                new InvalidValue("main", "lobby.health.value", 2049),
                new InvalidValue("main", "lobby.health.value", Double.NaN),
                new InvalidValue("main", "lobby.health.value", Double.POSITIVE_INFINITY),
                new InvalidValue("main", "lobby.health.value", "twenty"),
                new InvalidValue("main", "lobby.spawn.x", Double.NaN),
                new InvalidValue("main", "lobby.spawn.y", "up"),
                new InvalidValue("displays", "tab.interval-ticks", 1),
                new InvalidValue("displays", "tab.interval-ticks", 72001),
                new InvalidValue("displays", "tab.interval-ticks", "fast"),
                new InvalidValue("displays", "tab.interval-ticks", 2.5),
                new InvalidValue("displays", "scoreboard.interval-ticks", 1),
                new InvalidValue("displays", "announcements.interval-seconds", 9),
                new InvalidValue("items", "cooldown-ms", 99),
                new InvalidValue("items", "cooldown-ms", 60001));
    }

    private static Set<String> placeholders(String value) {
        Set<String> result = new HashSet<>();
        var matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static Fixtures defaults() throws Exception {
        return new Fixtures(resource("config.yml"), resource("displays.yml"), resource("items.yml"));
    }

    private static YamlConfiguration resource(String name) throws Exception {
        InputStream stream = ConfigBundleTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, "Missing packaged resource: " + name);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }

    private record Fixtures(FileConfiguration main, FileConfiguration displays, FileConfiguration items) {
        void validate() {
            ConfigBundle.validate(main, displays, items);
        }
    }

    private record InvalidValue(String file, String path, Object value) { }
}
