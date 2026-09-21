package me.vlosses.vLosses;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BridgeService implements PluginMessageListener, Listener {
    private static final String CHANNEL = "BungeeCord";
    private static final String SUBCHANNEL = "VLosses";
    private final VLosses plugin;
    private final BridgeCodec.ReplayGuard replay = new BridgeCodec.ReplayGuard(4096);
    private final Set<String> allowedServers = new HashSet<>();
    private final Map<UUID, Long> lastConnect = new HashMap<>();
    private BukkitTask pollTask;
    private BridgeCodec codec;
    private String serverName = "lobby";
    private String forwarding = "unknown";
    private boolean active;
    private boolean invalidSecret;
    private long intervalTicks;
    private long staleAfterMillis;
    private long lastResponse;
    private long lastQuery;
    private UUID countCarrier;
    private int cachedOnline;
    private long receiveWindow;
    private int receivedThisWindow;
    private long rejectedMessages;

    public BridgeService(VLosses plugin) {
        this.plugin = plugin;
    }

    public void start() {
        close();
        if (!plugin.getConfig().getBoolean("bridge.enabled", false)) {
            return;
        }
        serverName = plugin.getConfig().getString("bridge.server-name", "lobby");
        if (!BridgeCodec.validServer(serverName)) {
            plugin.tell(plugin.getServer().getConsoleSender(), "bridge-config-invalid", Map.of("detail", "bridge.server-name"));
            return;
        }
        for (String target : plugin.getConfig().getStringList("bridge.allowed-servers")) {
            if (allowedServers.size() >= 256) {
                break;
            }
            if (BridgeCodec.validServer(target)) {
                allowedServers.add(target);
            } else {
                plugin.tell(plugin.getServer().getConsoleSender(), "bridge-config-invalid", Map.of("detail", "bridge.allowed-servers"));
            }
        }
        intervalTicks = Math.clamp(plugin.getConfig().getLong("bridge.poll-interval-ticks", 100), 100, 72_000);
        staleAfterMillis = Math.max(30_000, intervalTicks * 150);
        if (plugin.getConfig().getBoolean("bridge.sync-language", false)) {
            try {
                codec = new BridgeCodec(plugin.getConfig().getString("bridge.secret", ""));
            } catch (IllegalArgumentException exception) {
                invalidSecret = true;
                plugin.tell(plugin.getServer().getConsoleSender(), "bridge-secret-invalid");
            }
        }
        forwarding = detectForwarding();
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        active = true;
        startPolling();
    }

    public boolean connect(Player player, String target) {
        if (!active) {
            plugin.tell(player, "bridge-disabled");
            return false;
        }
        if (target == null || !allowedServers.contains(target) || target.equals(serverName)) {
            plugin.tell(player, "bridge-target-denied");
            return false;
        }
        if (!player.hasPermission("vlosses.server." + target.toLowerCase(Locale.ROOT))) {
            plugin.tell(player, "no-permission");
            return false;
        }
        if (!player.isOnline()) {
            return false;
        }
        long now = System.nanoTime();
        Long previous = lastConnect.get(player.getUniqueId());
        if (previous != null && now - previous < 1_000_000_000L) {
            return false;
        }
        lastConnect.put(player.getUniqueId(), now);
        publishLanguage(player.getUniqueId(), plugin.language(player));
        player.sendPluginMessage(plugin, CHANNEL, message("Connect", target));
        plugin.tell(player, "bridge-connecting", Map.of("target", target));
        return true;
    }

    public void publishLanguage(UUID playerId, String language) {
        if (!active || codec == null || !plugin.isSupportedLanguage(language)) {
            return;
        }
        Player carrier = plugin.getServer().getPlayer(playerId);
        if (carrier == null || !carrier.isOnline()) {
            carrier = anyPlayer();
        }
        if (carrier == null) {
            return;
        }
        byte[] payload = codec.encode(new BridgeCodec.Preference(playerId, language, serverName,
                System.currentTimeMillis(), UUID.randomUUID()));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 32);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF("Forward");
            output.writeUTF("ALL");
            output.writeUTF(SUBCHANNEL);
            output.writeShort(payload.length);
            output.write(payload);
            carrier.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public int networkOnline() {
        int local = plugin.getServer().getOnlinePlayers().size();
        if (!active || lastResponse == 0 || System.currentTimeMillis() - lastResponse > staleAfterMillis) {
            return local;
        }
        return Math.max(local, cachedOnline);
    }

    public String status() {
        if (!active) {
            return "disabled";
        }
        String network = lastResponse == 0 ? "waiting" :
                System.currentTimeMillis() - lastResponse > staleAfterMillis ? "stale" : "online";
        return "network=" + network + ", language=" + (codec != null ? "signed" : invalidSecret ? "invalid-secret" : "disabled")
                + ", forwarding=" + forwarding + ", rejected=" + rejectedMessages;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!active || (!CHANNEL.equals(channel) && !"bungeecord:main".equals(channel))
                || message.length < 2 || message.length > BridgeCodec.MAX_PAYLOAD + 32) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < receiveWindow || now - receiveWindow >= 1_000) {
            receiveWindow = now;
            receivedThisWindow = 0;
        }
        if (++receivedThisWindow > 512) {
            rejectedMessages++;
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String subchannel = input.readUTF();
            if ("PlayerCount".equals(subchannel)) {
                String server = input.readUTF();
                int count = input.readInt();
                if ("ALL".equals(server) && input.available() == 0 && count >= 0 && count <= 1_000_000
                        && carrier.getUniqueId().equals(countCarrier) && now - lastQuery <= 15_000) {
                    cachedOnline = count;
                    lastResponse = now;
                    countCarrier = null;
                }
                return;
            }
            if (!SUBCHANNEL.equals(subchannel) || codec == null) {
                return;
            }
            int length = input.readUnsignedShort();
            if (length > BridgeCodec.MAX_PAYLOAD || length != input.available()) {
                rejectedMessages++;
                return;
            }
            BridgeCodec.Preference preference = codec.decode(input.readNBytes(length)).orElse(null);
            if (preference == null || preference.source().equals(serverName)
                    || !plugin.isSupportedLanguage(preference.language()) || !replay.accept(preference, now)) {
                rejectedMessages++;
                return;
            }
            plugin.setLanguage(preference.playerId(), preference.language(), false);
        } catch (IOException | IllegalArgumentException exception) {
            rejectedMessages++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        startPolling();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastConnect.remove(event.getPlayer().getUniqueId());
        if (plugin.getServer().getOnlinePlayers().size() <= 1) {
            stopPolling();
        }
    }

    public void close() {
        active = false;
        stopPolling();
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        codec = null;
        invalidSecret = false;
        allowedServers.clear();
        lastConnect.clear();
        replay.clear();
        cachedOnline = 0;
        lastResponse = 0;
        lastQuery = 0;
        rejectedMessages = 0;
        receiveWindow = 0;
        receivedThisWindow = 0;
    }

    private void startPolling() {
        if (active && pollTask == null && !plugin.getServer().getOnlinePlayers().isEmpty()) {
            pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 20, intervalTicks);
        }
    }

    private void poll() {
        Player carrier = anyPlayer();
        if (carrier == null) {
            stopPolling();
            return;
        }
        countCarrier = carrier.getUniqueId();
        lastQuery = System.currentTimeMillis();
        carrier.sendPluginMessage(plugin, CHANNEL, message("PlayerCount", "ALL"));
    }

    private void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        countCarrier = null;
    }

    private Player anyPlayer() {
        return plugin.getServer().getOnlinePlayers().stream().findFirst().orElse(null);
    }

    private String detectForwarding() {
        File global = new File("config", "paper-global.yml");
        if (global.isFile() && YamlConfiguration.loadConfiguration(global).getBoolean("proxies.velocity.enabled")) {
            return "velocity-modern";
        }
        File spigot = new File("spigot.yml");
        if (spigot.isFile() && YamlConfiguration.loadConfiguration(spigot).getBoolean("settings.bungeecord")) {
            return "bungeecord";
        }
        return "undetected";
    }

    private static byte[] message(String operation, String argument) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(operation);
            output.writeUTF(argument);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
