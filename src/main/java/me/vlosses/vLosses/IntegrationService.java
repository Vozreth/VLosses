package me.vlosses.vLosses;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class IntegrationService implements Listener, AutoCloseable {
    private final VLosses plugin;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean active;
    private AutoCloseable hook;

    public IntegrationService(VLosses plugin) { this.plugin = plugin; }
    public void start() {
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        attach();
    }
    private void attach() {
        if (hook != null || !plugin.getConfig().getBoolean("integrations.luckperms", true)
                || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return;
        try { hook = LuckPermsHook.attach(plugin, this::enqueue); }
        catch (RuntimeException | LinkageError e) { plugin.warn("LuckPerms: " + e.getClass().getSimpleName()); }
    }
    private void enqueue(UUID id) {
        if (!active) return;
        if (pending.size() < 10000) pending.add(id);
        if (scheduled.compareAndSet(false, true)) {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    scheduled.set(false);
                    if (!active) return;
                    for (UUID value : Set.copyOf(pending)) {
                        pending.remove(value);
                        plugin.refreshPlayer(value);
                    }
                });
            } catch (RuntimeException e) { scheduled.set(false); pending.clear(); }
        }
    }
    @EventHandler public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("LuckPerms")) attach();
    }
    @EventHandler public void disabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("LuckPerms")) detach();
    }
    private void detach() {
        if (hook != null) try { hook.close(); } catch (Exception e) { plugin.warn("LuckPerms: " + e.getClass().getSimpleName()); }
        hook = null;
    }
    @Override public void close() { active = false; detach(); pending.clear(); HandlerList.unregisterAll(this); }
    private static final class LuckPermsHook {
        static AutoCloseable attach(VLosses plugin, java.util.function.Consumer<UUID> changed) {
            var subscription = net.luckperms.api.LuckPermsProvider.get().getEventBus().subscribe(plugin,
                    net.luckperms.api.event.user.UserDataRecalculateEvent.class,
                    event -> changed.accept(event.getUser().getUniqueId()));
            return subscription::close;
        }
    }
}
