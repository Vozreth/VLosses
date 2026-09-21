package me.vlosses.vLosses;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class VLosses extends JavaPlugin implements Listener {
    private ConfigBundle settings;
    private PreferencesStore preferences;
    private TextService texts;
    private DisplayService displays;
    private LobbyService lobby;
    private ItemService items;
    private BridgeService bridge;
    private IntegrationService integrations;
    private Set<String> worlds = Set.of();
    private final Set<UUID> pendingRefresh = new HashSet<>();
    private final Map<String, Long> warnings = new LinkedHashMap<>();

    @Override public void onEnable() {
        try {
            settings = ConfigBundle.load(this);
            preferences = new PreferencesStore(this);
            startServices();
            getServer().getPluginManager().registerEvents(this, this);
            for (String name : List.of("vlosses", "lobby", "discord", "vote", "website")) {
                var command = Objects.requireNonNull(getCommand(name));
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
            startMetrics();
            tell(Bukkit.getConsoleSender(), "enabled");
            for (String issue : issues()) getLogger().warning(issue);
        } catch (Exception | LinkageError e) {
            getLogger().severe("VLosses: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void startServices() {
        worlds = Set.copyOf(getConfig().getStringList("lobby.worlds"));
        bridge = new BridgeService(this);
        texts = new TextService(this);
        lobby = new LobbyService(this);
        items = new ItemService(this);
        displays = new DisplayService(this);
        bridge.start(); lobby.start(); items.start(); displays.start();
        integrations = new IntegrationService(this);
        integrations.start();
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
    }

    /** Starts anonymous, server-owner-controlled bStats reporting. */
    private void startMetrics() {
        try {
            new Metrics(this, 34180);
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("bStats could not start: " + e.getClass().getSimpleName());
        }
    }

    private void stopServices() {
        if (integrations != null) { integrations.close(); integrations = null; }
        if (displays != null) { displays.close(); displays = null; }
        if (items != null) { items.close(); items = null; }
        if (lobby != null) { lobby.close(); lobby = null; }
        if (texts != null) { texts.close(); texts = null; }
        if (bridge != null) { bridge.close(); bridge = null; }
    }

    @Override public void onDisable() {
        HandlerList.unregisterAll((Listener) this);
        stopServices();
        if (preferences != null) preferences.close();
        Bukkit.getScheduler().cancelTasks(this);
        pendingRefresh.clear(); warnings.clear();
    }

    @Override public FileConfiguration getConfig() { return settings == null ? super.getConfig() : settings.main(); }
    @Override public void saveConfig() {
        try { getConfig().save(new File(getDataFolder(), "config.yml")); }
        catch (IOException e) { throw new IllegalStateException("config.yml: " + e.getMessage(), e); }
    }
    public FileConfiguration displaysConfig() { return settings.displays(); }
    public FileConfiguration itemsConfig() { return settings.items(); }
    public BridgeService bridge() { return bridge; }
    public int networkOnline() { return bridge == null ? Bukkit.getOnlinePlayers().size() : bridge.networkOnline(); }
    public boolean isLobby(Player player) { return isLobby(player.getWorld()); }
    public boolean isLobby(World world) { return world != null && worlds.contains(world.getName()); }
    public boolean teleportSpawn(Player player) { return lobby != null && lobby.teleportSpawn(player); }
    public boolean isSupportedLanguage(String language) { return ConfigBundle.LANGUAGES.contains(language); }
    public String locale(Player player) { return language(player); }

    public String language(Player player) {
        if (player != null && preferences != null) {
            String selected = preferences.get(player.getUniqueId());
            if (selected != null) return selected;
            if (getConfig().getBoolean("language.use-client-locale", true)) {
                String language = player.locale().getLanguage().toLowerCase(Locale.ROOT);
                if (isSupportedLanguage(language)) return language;
            }
        }
        return getConfig().getString("language.default", "tr");
    }

    public void setLanguage(UUID id, String language, boolean broadcast) {
        if (language != null && !isSupportedLanguage(language)) return;
        preferences.set(id, language);
        Player player = Bukkit.getPlayer(id);
        if (player != null) queueRefresh(player);
        if (broadcast && bridge != null && language != null) bridge.publishLanguage(id, language);
    }

    public String rawMessage(Player player, String key) {
        if (settings == null) return key;
        FileConfiguration language = settings.languages().get(language(player));
        String fallback = settings.languages().get("en").getString(key, key);
        return language == null ? fallback : language.getString(key, fallback);
    }

    public Component text(Player player, String template) {
        return texts == null ? Component.text(template) : texts.render(player, template);
    }

    public void tell(CommandSender recipient, String key) { tell(recipient, key, Map.of()); }
    public void tell(CommandSender recipient, String key, Map<String, String> values) {
        Player player = recipient instanceof Player p ? p : null;
        Component message = text(player, rawMessage(player, key));
        for (var entry : values.entrySet()) message = message.replaceText(builder -> builder.matchLiteral("{" + entry.getKey() + "}").replacement(Component.text(entry.getValue())));
        recipient.sendMessage(message);
    }

    public void warn(String message) {
        long now = System.currentTimeMillis();
        synchronized (warnings) {
            Long previous = warnings.get(message);
            if (previous != null && now - previous < 60_000) return;
            if (warnings.size() >= 128) warnings.remove(warnings.keySet().iterator().next());
            warnings.put(message, now);
        }
        getLogger().warning(message);
    }

    private void refresh(Player player) {
        if (!player.isOnline() || displays == null) return;
        if (isLobby(player)) { lobby.enter(player); items.give(player); displays.refresh(player); }
        else { displays.remove(player); items.remove(player); lobby.leave(player); }
    }

    private void queueRefresh(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingRefresh.add(id)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            pendingRefresh.remove(id);
            Player current = Bukkit.getPlayer(id);
            if (current != null) refresh(current);
        });
    }

    public void refreshPlayer(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player != null) queueRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) { queueRefresh(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void world(PlayerChangedWorldEvent event) { queueRefresh(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void locale(PlayerLocaleChangeEvent event) { queueRefresh(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent event) { queueRefresh(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer(); pendingRefresh.remove(player.getUniqueId());
        if (displays != null) displays.remove(player);
        if (items != null) items.remove(player);
        if (lobby != null) lobby.leave(player);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vlosses.use")) { tell(sender, "no-permission"); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!command.getName().equalsIgnoreCase("vlosses")) {
            action = command.getName().equalsIgnoreCase("lobby") ? "spawn" : command.getName().toLowerCase(Locale.ROOT);
        }
        if (List.of("reload", "setspawn", "diagnostics", "doctor", "announce").contains(action) && !sender.hasPermission("vlosses.admin")) {
            tell(sender, "no-permission"); return true;
        }
        switch (action) {
            case "help" -> { tell(sender, "help"); if (sender.hasPermission("vlosses.admin")) tell(sender, "help-admin"); }
            case "reload" -> reload(sender);
            case "diagnostics", "doctor" -> diagnostics(sender);
            case "language", "lang" -> {
                if (!(sender instanceof Player player)) { tell(sender, "player-only"); break; }
                if (!player.hasPermission("vlosses.language")) { tell(sender, "no-permission"); break; }
                if (args.length != 2 || (!isSupportedLanguage(args[1].toLowerCase(Locale.ROOT)) && !args[1].equalsIgnoreCase("auto"))) {
                    tell(sender, "language-usage"); break;
                }
                setLanguage(player.getUniqueId(), args[1].equalsIgnoreCase("auto") ? null : args[1].toLowerCase(Locale.ROOT), true);
                tell(sender, "language-changed", Map.of("language", language(player)));
            }
            case "spawn" -> {
                if (!(sender instanceof Player player)) tell(sender, "player-only");
                else if (!player.hasPermission("vlosses.spawn")) tell(sender, "no-permission");
                else teleportSpawn(player);
            }
            case "setspawn" -> {
                if (!(sender instanceof Player player)) tell(sender, "player-only");
                else if (!isLobby(player)) tell(sender, "lobby-only");
                else try { if (lobby.setSpawn(player)) tell(sender, "spawn-set"); }
                catch (Exception e) { tell(sender, "save-failed", Map.of("detail", String.valueOf(e.getMessage()))); }
            }
            case "server" -> {
                if (!(sender instanceof Player player)) tell(sender, "player-only");
                else if (args.length != 2) tell(sender, "server-usage");
                else bridge.connect(player, args[1]);
            }
            case "announce" -> announce(sender, args);
            case "vote", "discord", "website" -> link(sender, action);
            default -> tell(sender, "help");
        }
        return true;
    }

    private void link(CommandSender sender, String name) {
        String value = getConfig().getString("links." + name, "").trim();
        try {
            java.net.URI url = java.net.URI.create(value);
            if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())) || url.getHost() == null || url.getUserInfo() != null) throw new IllegalArgumentException();
            Player player = sender instanceof Player p ? p : null;
            sender.sendMessage(text(player, rawMessage(player, "link-" + name)).clickEvent(ClickEvent.openUrl(url.toString())));
        } catch (IllegalArgumentException e) { tell(sender, "link-unavailable"); }
    }

    private void announce(CommandSender sender, String[] args) {
        if (args.length != 2) { tell(sender, "announce-usage"); return; }
        var entries = displaysConfig().getMapList("announcements.entries");
        int index;
        try { index = Integer.parseInt(args[1]) - 1; }
        catch (NumberFormatException e) { tell(sender, "announce-usage"); return; }
        if (index < 0 || index >= entries.size()) { tell(sender, "announce-usage"); return; }
        var entry = entries.get(index);
        if (!(entry.get("messages") instanceof Map<?, ?> messages)) return;
        String permission = String.valueOf(entry.containsKey("permission") ? entry.get("permission") : "");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isLobby(player) || !permission.isBlank() && !player.hasPermission(permission)) continue;
            Object value = messages.get(language(player));
            if (value == null) value = messages.get("en");
            if (value != null) player.sendMessage(text(player, String.valueOf(value)));
        }
        tell(sender, "announced");
    }

    private void reload(CommandSender sender) {
        ConfigBundle previous = settings;
        try {
            ConfigBundle next = ConfigBundle.load(this);
            stopServices(); settings = next; startServices();
            tell(sender, "reloaded");
            for (String issue : issues()) sender.sendMessage(Component.text(issue));
        } catch (Exception | LinkageError e) {
            if (settings != previous || displays == null) {
                stopServices(); settings = previous;
                try { startServices(); }
                catch (Exception | LinkageError failed) { getLogger().severe(String.valueOf(failed.getMessage())); Bukkit.getPluginManager().disablePlugin(this); }
            }
            tell(sender, "reload-failed", Map.of("detail", String.valueOf(e.getMessage())));
        }
    }

    private List<String> issues() {
        List<String> issues = new ArrayList<>();
        for (String world : worlds) if (Bukkit.getWorld(world) == null) issues.add(plain("diagnostics-world") + " " + world);
        if (getConfig().getBoolean("integrations.luckperms", true) && !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) issues.add(plain("diagnostics-luckperms"));
        if (getConfig().getBoolean("integrations.placeholderapi", true) && !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) issues.add(plain("diagnostics-placeholderapi"));
        for (String conflict : List.of("TAB", "AnimatedScoreboard", "FeatherBoard", "DeluxeHub", "ItemJoin")) {
            if (Bukkit.getPluginManager().isPluginEnabled(conflict)) issues.add(plain("diagnostics-conflict") + " " + conflict);
        }
        if (preferences != null && !preferences.writable()) issues.add(plain("diagnostics-preferences"));
        return issues;
    }

    private String plain(String key) { return PlainTextComponentSerializer.plainText().serialize(text(null, rawMessage(null, key))); }

    private void diagnostics(CommandSender sender) {
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576;
        long maximum = Runtime.getRuntime().maxMemory() / 1048576;
        long managed = Bukkit.getOnlinePlayers().stream().filter(this::isLobby).count();
        tell(sender, "diagnostics", Map.of("version", getPluginMeta().getVersion(), "players", String.valueOf(managed),
                "tps", String.format(Locale.ROOT, "%.2f", Math.min(20, Bukkit.getTPS()[0])),
                "mspt", String.format(Locale.ROOT, "%.2f", Bukkit.getAverageTickTime()),
                "memory", used + "/" + maximum, "bridge", bridge.status()));
        List<String> issues = issues();
        if (issues.isEmpty()) tell(sender, "diagnostics-ok");
        else issues.forEach(issue -> sender.sendMessage(Component.text(issue)));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("vlosses")) return List.of();
        if (!sender.hasPermission("vlosses.use")) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.addAll(List.of("help", "spawn", "language", "server", "vote", "discord", "website"));
            if (sender.hasPermission("vlosses.admin")) choices.addAll(List.of("reload", "setspawn", "diagnostics", "announce"));
        } else if (args.length == 2 && List.of("language", "lang").contains(args[0].toLowerCase(Locale.ROOT))) {
            choices.addAll(ConfigBundle.LANGUAGES); choices.add("auto");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("server")) {
            choices.addAll(getConfig().getStringList("bridge.allowed-servers").stream().filter(server -> sender.hasPermission("vlosses.server." + server)).toList());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
