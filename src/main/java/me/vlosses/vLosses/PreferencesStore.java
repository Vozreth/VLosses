package me.vlosses.vLosses;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class PreferencesStore implements AutoCloseable {
    private final Map<UUID, String> languages = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final Path path;
    private final Logger logger;
    private long savedRevision;
    private BukkitTask task;
    private final boolean initiallyWritable;
    private volatile boolean lastSaveFailed;

    public PreferencesStore(VLosses plugin) {
        path = plugin.getDataFolder().toPath().resolve("players.yml");
        logger = plugin.getLogger();
        boolean loaded = true;
        if (Files.exists(path)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(path.toFile());
                for (String key : yaml.getKeys(false)) {
                    try {
                        String value = yaml.getString(key);
                        if (ConfigBundle.LANGUAGES.contains(value)) languages.put(UUID.fromString(key), value);
                    } catch (IllegalArgumentException ignored) { }
                }
            } catch (Exception e) {
                loaded = false;
                logger.severe("players.yml: " + e.getMessage());
            }
        }
        initiallyWritable = loaded;
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::save, 1200L, 1200L);
    }

    public String get(UUID id) { return languages.get(id); }

    public void set(UUID id, String language) {
        String previous = language == null ? languages.remove(id) : languages.put(id, language);
        if (!java.util.Objects.equals(previous, language)) revision.incrementAndGet();
    }

    public boolean writable() { return initiallyWritable && !lastSaveFailed; }

    private synchronized void save() {
        long current = revision.get();
        if (!initiallyWritable || current == savedRevision) return;
        try {
            Map<UUID, String> snapshot = new HashMap<>(languages);
            YamlConfiguration yaml = new YamlConfiguration();
            snapshot.forEach((key, value) -> yaml.set(key.toString(), value));
            Path temporary = path.resolveSibling("players.yml.tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            savedRevision = current;
            lastSaveFailed = false;
        } catch (Exception e) {
            lastSaveFailed = true;
            logger.severe("players.yml: " + e.getMessage());
        }
    }

    @Override public void close() {
        if (task != null) task.cancel();
        save();
    }
}
