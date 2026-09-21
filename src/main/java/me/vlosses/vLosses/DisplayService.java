package me.vlosses.vLosses;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisplayService implements AutoCloseable {
    private static final Pattern ANIMATION = Pattern.compile("\\{animation:([a-zA-Z0-9_.-]+)}");
    private static final int MAX_LINES = 15;
    private final VLosses plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<String, Animation> animations = new LinkedHashMap<>();
    private final List<Announcement> announcements = new ArrayList<>();
    private boolean tabEnabled;
    private boolean scoreboardEnabled;
    private boolean overrideExisting;
    private List<String> headers = List.of();
    private List<String> footers = List.of();
    private List<String> titles = List.of();
    private List<String> lines = List.of();
    private String playerName;
    private int tabInterval;
    private int scoreboardInterval;
    private int tabCadence;
    private int scoreboardCadence;
    private int period;
    private long announcementInterval;
    private long nextAnnouncement;
    private long elapsed;
    private long updates;
    private long lastRunMicros;
    private long lastWarning;
    private int announcementIndex;
    private BukkitTask task;

    public DisplayService(VLosses plugin) {
        this.plugin = plugin;
    }

    public void start() {
        close();
        FileConfiguration config = plugin.displaysConfig();
        tabEnabled = config.getBoolean("tab.enabled", true);
        scoreboardEnabled = config.getBoolean("scoreboard.enabled", true);
        overrideExisting = config.getBoolean("scoreboard.override-existing", false);
        tabInterval = interval(config.getLong("tab.interval-ticks", 20));
        scoreboardInterval = interval(config.getLong("scoreboard.interval-ticks", 20));
        headers = stringFrames(config, "tab.header");
        footers = stringFrames(config, "tab.footer");
        titles = stringFrames(config, "scoreboard.title");
        playerName = config.getString("tab.player-name", "{prefix}{player}{suffix}");
        List<String> configuredLines = config.getStringList("scoreboard.lines");
        if (configuredLines.size() > MAX_LINES) plugin.warn("displays.yml: scoreboard.lines supports at most 15 lines; extra lines were ignored.");
        lines = List.copyOf(configuredLines.subList(0, Math.min(MAX_LINES, configuredLines.size())));
        ConfigurationSection section = config.getConfigurationSection("animations");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                List<String> frames = stringFrames(section, name + ".frames");
                if (!frames.isEmpty()) animations.put(name, new Animation(interval(section.getLong(name + ".interval-ticks", 20)), frames));
            }
        }
        tabCadence = cadence(tabInterval, headers, footers, List.of(playerName));
        scoreboardCadence = cadence(scoreboardInterval, titles, lines);
        if (config.getBoolean("announcements.enabled", true)) {
            for (Map<?, ?> entry : config.getMapList("announcements.entries")) {
                Object rawMessages = entry.get("messages");
                if (!(rawMessages instanceof Map<?, ?> source)) {
                    plugin.warn("displays.yml: an announcement needs a messages language map.");
                    continue;
                }
                Map<String, String> messages = new LinkedHashMap<>();
                source.forEach((key, value) -> {
                    if (key instanceof String language && value instanceof String message && !message.isBlank())
                        messages.put(language.toLowerCase(Locale.ROOT).replace('-', '_'), message);
                });
                if (messages.isEmpty()) continue;
                String channel = Objects.toString(entry.get("channel"), "CHAT").toUpperCase(Locale.ROOT);
                if (!List.of("CHAT", "ACTION_BAR", "TITLE").contains(channel)) {
                    plugin.warn("displays.yml: unknown announcement channel " + channel + "; using CHAT.");
                    channel = "CHAT";
                }
                announcements.add(new Announcement(channel, Objects.toString(entry.get("permission"), ""), messages));
            }
        }
        announcementInterval = Math.max(5, Math.min(86_400, config.getLong("announcements.interval-seconds", 120))) * 20L;
        nextAnnouncement = announcementInterval;
        period = 0;
        if (tabEnabled) period = tabCadence;
        if (scoreboardEnabled) period = gcd(period, scoreboardCadence);
        if (!announcements.isEmpty()) period = gcd(period, (int) announcementInterval);
        if (period == 0) return;
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    private void tick() {
        long started = System.nanoTime();
        elapsed += period;
        boolean tabDue = tabEnabled && elapsed % tabCadence == 0;
        boolean boardDue = scoreboardEnabled && elapsed % scoreboardCadence == 0;
        boolean announceDue = !announcements.isEmpty() && elapsed >= nextAnnouncement;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.isLobby(player)) {
                if (states.containsKey(player.getUniqueId())) remove(player);
                continue;
            }
            try {
                if (tabDue || boardDue) update(player, tabDue, boardDue);
                if (announceDue) announce(player);
            } catch (RuntimeException exception) {
                warn("Display update failed for " + player.getName() + ": " + exception.getClass().getSimpleName());
            }
        }
        if (announceDue) {
            nextAnnouncement = elapsed + announcementInterval;
            announcementIndex = (announcementIndex + 1) % announcements.size();
        }
        lastRunMicros = (System.nanoTime() - started) / 1_000;
    }

    public void refresh(Player player) {
        if (!player.isOnline() || !plugin.isLobby(player)) {
            remove(player);
            return;
        }
        try {
            update(player, tabEnabled, scoreboardEnabled);
        } catch (RuntimeException exception) {
            warn("Display initialization failed for " + player.getName() + ": " + exception.getClass().getSimpleName());
        }
    }

    private void update(Player player, boolean updateTab, boolean updateBoard) {
        if (!updateTab && !updateBoard) return;
        State state = states.computeIfAbsent(player.getUniqueId(), ignored -> new State(player));
        if (updateTab) {
            Component header = render(player, frame(headers, elapsed, tabInterval));
            Component footer = render(player, frame(footers, elapsed, tabInterval));
            if (!header.equals(state.lastHeader) || !footer.equals(state.lastFooter)) {
                player.sendPlayerListHeaderAndFooter(header, footer);
                state.lastHeader = header;
                state.lastFooter = footer;
                updates++;
            }
            if (!playerName.isEmpty()) {
                Component name = render(player, playerName);
                if (!name.equals(state.lastName)) {
                    player.playerListName(name);
                    state.lastName = name;
                    updates++;
                }
            }
        }
        if (updateBoard) scoreboard(player, state);
    }

    private void scoreboard(Player player, State state) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        Scoreboard current = player.getScoreboard();
        boolean attach = false;
        if (state.board != null && current != state.board) {
            if (!overrideExisting) return;
            state.previousBoard = current;
            attach = true;
        }
        if (state.board == null) {
            if (!overrideExisting && (current != manager.getMainScoreboard() || !current.getObjectives().isEmpty() || !current.getTeams().isEmpty())) {
                if (!state.conflictReported) {
                    state.conflictReported = true;
                    warn("A scoreboard is already assigned; VLosses keeps it. Check displays.yml: scoreboard.override-existing if replacement is intended.");
                }
                return;
            }
            state.previousBoard = current;
            state.board = manager.getNewScoreboard();
            state.objective = state.board.registerNewObjective("vlosses_lobby", Criteria.DUMMY, Component.empty());
            state.objective.numberFormat(NumberFormat.blank());
            state.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int index = 0; index < lines.size(); index++) {
                Score score = state.objective.getScore(entryKey(index));
                score.setScore(MAX_LINES - index);
                state.scores.add(score);
                state.lastLines.add(null);
            }
            attach = true;
        }
        Component title = render(player, frame(titles, elapsed, scoreboardInterval));
        if (!title.equals(state.lastTitle)) {
            state.objective.displayName(title);
            state.lastTitle = title;
            updates++;
        }
        for (int index = 0; index < lines.size(); index++) {
            Component line = render(player, lines.get(index));
            if (!line.equals(state.lastLines.get(index))) {
                state.scores.get(index).customName(line);
                state.lastLines.set(index, line);
                updates++;
            }
        }
        if (attach) {
            player.setScoreboard(state.board);
            updates++;
        }
    }

    private void announce(Player player) {
        for (int offset = 0; offset < announcements.size(); offset++) {
            Announcement entry = announcements.get((announcementIndex + offset) % announcements.size());
            if (!entry.permission.isEmpty() && !player.hasPermission(entry.permission)) continue;
            String language = plugin.language(player).toLowerCase(Locale.ROOT).replace('-', '_');
            String text = entry.messages.get(language);
            if (text == null && language.contains("_")) text = entry.messages.get(language.substring(0, language.indexOf('_')));
            if (text == null) text = entry.messages.get("en");
            if (text == null) text = entry.messages.values().iterator().next();
            switch (entry.channel) {
                case "ACTION_BAR" -> player.sendActionBar(render(player, text));
                case "TITLE" -> {
                    String[] parts = text.split("\n", 2);
                    player.showTitle(Title.title(render(player, parts[0]), parts.length > 1 ? render(player, parts[1]) : Component.empty(),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))));
                }
                default -> player.sendMessage(render(player, text));
            }
            updates++;
            return;
        }
    }

    private Component render(Player player, String input) {
        Matcher matcher = ANIMATION.matcher(input);
        StringBuilder replaced = new StringBuilder(input.length());
        while (matcher.find()) {
            Animation animation = animations.get(matcher.group(1));
            String replacement = animation == null ? matcher.group() : frame(animation.frames, elapsed, animation.interval);
            matcher.appendReplacement(replaced, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(replaced);
        return plugin.text(player, replaced.toString());
    }

    @SafeVarargs
    private int cadence(int base, List<String>... groups) {
        int result = base;
        for (List<String> group : groups) {
            for (String text : group) {
                Matcher matcher = ANIMATION.matcher(text);
                while (matcher.find()) {
                    Animation animation = animations.get(matcher.group(1));
                    if (animation == null) plugin.warn("displays.yml: missing animation " + matcher.group(1));
                    else result = gcd(result, animation.interval);
                }
            }
        }
        return result;
    }

    public void remove(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state == null) return;
        if (state.board != null && player.getScoreboard() == state.board && state.previousBoard != null) player.setScoreboard(state.previousBoard);
        if (state.lastHeader != null && Objects.equals(player.playerListHeader(), state.lastHeader))
            player.sendPlayerListHeader(orEmpty(state.previousHeader));
        if (state.lastFooter != null && Objects.equals(player.playerListFooter(), state.lastFooter))
            player.sendPlayerListFooter(orEmpty(state.previousFooter));
        if (state.lastName != null && Objects.equals(player.playerListName(), state.lastName)) player.playerListName(state.previousName);
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : List.copyOf(states.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) remove(player);
        }
        states.clear();
        animations.clear();
        announcements.clear();
        elapsed = 0;
        announcementIndex = 0;
    }

    public long updateCount() {
        return updates;
    }

    public long lastRunMicros() {
        return lastRunMicros;
    }

    public int activePlayers() {
        return states.size();
    }

    private void warn(String message) {
        long now = System.nanoTime();
        if (lastWarning != 0 && now - lastWarning < 60_000_000_000L) return;
        lastWarning = now;
        plugin.warn(message);
    }

    static String frame(List<String> frames, long ticks, int interval) {
        if (frames.isEmpty()) return "";
        return frames.get((int) Math.floorMod(Math.floorDiv(ticks, Math.max(1, interval)), frames.size()));
    }

    static String entryKey(int index) {
        if (index < 0 || index >= MAX_LINES) throw new IllegalArgumentException("Sidebar index must be between 0 and 14");
        return "vlosses_line_" + index;
    }

    private static int interval(long value) {
        return (int) Math.max(2, Math.min(72_000, value));
    }

    private static int gcd(int first, int second) {
        while (second != 0) {
            int remainder = first % second;
            first = second;
            second = remainder;
        }
        return Math.max(1, first);
    }

    private static List<String> stringFrames(ConfigurationSection config, String key) {
        if (config.isString(key)) return List.of(Objects.requireNonNullElse(config.getString(key), ""));
        return List.copyOf(config.getStringList(key));
    }

    private static Component orEmpty(Component component) {
        return component == null ? Component.empty() : component;
    }

    private record Animation(int interval, List<String> frames) {}
    private record Announcement(String channel, String permission, Map<String, String> messages) {}

    private static final class State {
        private final Component previousHeader;
        private final Component previousFooter;
        private final Component previousName;
        private Scoreboard previousBoard;
        private Scoreboard board;
        private Objective objective;
        private final List<Score> scores = new ArrayList<>();
        private final List<Component> lastLines = new ArrayList<>();
        private Component lastHeader;
        private Component lastFooter;
        private Component lastName;
        private Component lastTitle;
        private boolean conflictReported;

        private State(Player player) {
            previousHeader = player.playerListHeader();
            previousFooter = player.playerListFooter();
            previousName = player.playerListName();
        }
    }
}
