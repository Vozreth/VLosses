package me.vlosses.vLosses;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class LobbyService implements Listener, AutoCloseable {
    private final VLosses plugin;
    private final Map<UUID, Environment> environments = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private final Set<UUID> respawned = new HashSet<>();
    private final Map<UUID, Long> nextWarnings = new HashMap<>();
    private boolean active;
    private boolean hunger;
    private boolean damage;
    private boolean fall;
    private boolean voidProtection;
    private boolean blockBreak;
    private boolean blockPlace;
    private boolean interact;
    private boolean dropItems;
    private boolean mobSpawning;
    private boolean explosions;
    private boolean fireSpread;
    private boolean entityGriefing;
    private boolean health;
    private double healthValue;
    private boolean fixedTime;
    private long time;
    private boolean fixedWeather;
    private WeatherType weather;
    private boolean teleportOnJoin;
    private boolean teleportOnRespawn;

    public LobbyService(VLosses plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (active) return;
        var config = plugin.getConfig();
        hunger = config.getBoolean("lobby.protection.hunger", true);
        damage = config.getBoolean("lobby.protection.damage", true);
        fall = config.getBoolean("lobby.protection.fall", true);
        voidProtection = config.getBoolean("lobby.protection.void", true);
        blockBreak = config.getBoolean("lobby.protection.block-break", true);
        blockPlace = config.getBoolean("lobby.protection.block-place", true);
        interact = config.getBoolean("lobby.protection.interact", false);
        dropItems = config.getBoolean("lobby.protection.drop-items", true);
        mobSpawning = config.getBoolean("lobby.protection.mob-spawning", true);
        explosions = config.getBoolean("lobby.protection.explosions", true);
        fireSpread = config.getBoolean("lobby.protection.fire-spread", true);
        entityGriefing = config.getBoolean("lobby.protection.entity-griefing", true);
        health = config.getBoolean("lobby.health.enabled", true);
        healthValue = config.getDouble("lobby.health.value", 20.0);
        if (!Double.isFinite(healthValue) || healthValue <= 0) healthValue = 20.0;
        fixedTime = config.getBoolean("lobby.time.enabled", true);
        time = Math.floorMod(config.getLong("lobby.time.ticks", 6000L), 24000L);
        fixedWeather = config.getBoolean("lobby.weather.enabled", true);
        weather = "DOWNFALL".equalsIgnoreCase(config.getString("lobby.weather.type", "CLEAR"))
                ? WeatherType.DOWNFALL : WeatherType.CLEAR;
        teleportOnJoin = config.getBoolean("lobby.teleport-on-join", true);
        teleportOnRespawn = config.getBoolean("lobby.teleport-on-respawn", true);
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void enter(Player player) {
        if (!active || !plugin.isLobby(player)) return;
        boolean afterRespawn = respawned.remove(player.getUniqueId());
        boolean resetVitals = !environments.containsKey(player.getUniqueId()) || afterRespawn;
        environments.computeIfAbsent(player.getUniqueId(), ignored ->
                new Environment(player.getPlayerTimeOffset(), player.isPlayerTimeRelative(), player.getPlayerWeather()));
        if (fixedTime && (player.isPlayerTimeRelative() || player.getPlayerTimeOffset() != time)) player.setPlayerTime(time, false);
        if (fixedWeather && player.getPlayerWeather() != weather) player.setPlayerWeather(weather);
        if (player.hasPermission("vlosses.bypass")) return;
        if (hunger && resetVitals) {
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setExhaustion(0.0F);
        }
        if (health && resetVitals && !player.isDead()) {
            AttributeInstance maximum = player.getAttribute(Attribute.MAX_HEALTH);
            double limit = maximum == null ? 20.0 : maximum.getValue();
            player.setHealth(Math.max(0.01, Math.min(healthValue, limit)));
        }
        if (fall) player.setFallDistance(0);
        if (damage) player.setFireTicks(0);
    }

    public void leave(Player player) {
        nextWarnings.remove(player.getUniqueId());
        respawned.remove(player.getUniqueId());
        Environment previous = environments.remove(player.getUniqueId());
        if (previous == null) return;
        if (fixedTime) player.setPlayerTime(previous.time(), previous.relative());
        if (fixedWeather) {
            if (previous.weather() == null) player.resetPlayerWeather();
            else player.setPlayerWeather(previous.weather());
        }
    }

    public boolean setSpawn(Player player) {
        if (!plugin.isLobby(player)) return false;
        Location location = player.getLocation();
        var config = plugin.getConfig();
        config.set("lobby.spawn.world", player.getWorld().getName());
        config.set("lobby.spawn.x", location.getX());
        config.set("lobby.spawn.y", location.getY());
        config.set("lobby.spawn.z", location.getZ());
        config.set("lobby.spawn.yaw", location.getYaw());
        config.set("lobby.spawn.pitch", location.getPitch());
        plugin.saveConfig();
        return true;
    }

    public boolean teleportSpawn(Player player) {
        if (!active || !player.isOnline()) return false;
        UUID id = player.getUniqueId();
        if (teleporting.contains(id)) return true;
        Location target = spawn();
        if (target == null) {
            teleportWarning(player, "spawn-unavailable", null);
            return false;
        }
        teleporting.add(id);
        try {
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, failure) -> finishTeleport(id, Boolean.TRUE.equals(success), failure));
            return true;
        } catch (RuntimeException exception) {
            teleporting.remove(id);
            teleportWarning(player, "teleport-failed", exception);
            return false;
        }
    }

    private void finishTeleport(UUID id, boolean success, Throwable failure) {
        if (!active || !plugin.isEnabled()) return;
        Runnable finish = () -> {
            teleporting.remove(id);
            if (!active) return;
            Player online = Bukkit.getPlayer(id);
            if (online == null) return;
            if (success) online.setFallDistance(0);
            else teleportWarning(online, "teleport-failed", failure);
        };
        if (Bukkit.isPrimaryThread()) finish.run();
        else Bukkit.getScheduler().runTask(plugin, finish);
    }

    private void teleportWarning(Player player, String key, Throwable failure) {
        long now = System.nanoTime();
        Long next = nextWarnings.get(player.getUniqueId());
        if (next != null && now - next < 0) return;
        nextWarnings.put(player.getUniqueId(), now + TimeUnit.SECONDS.toNanos(10));
        plugin.tell(player, key);
        if (failure != null) plugin.getLogger().warning("Spawn teleport failed: " + failure.getClass().getSimpleName());
    }

    private Location spawn() {
        var config = plugin.getConfig();
        String name = config.getString("lobby.spawn.world", "");
        World world = name.isBlank() ? null : Bukkit.getWorld(name);
        if (world == null || !plugin.isLobby(world)) {
            return Bukkit.getWorlds().stream().filter(plugin::isLobby).findFirst()
                    .map(World::getSpawnLocation).orElse(null);
        }
        if (!config.contains("lobby.spawn.x") || !config.contains("lobby.spawn.y") || !config.contains("lobby.spawn.z")) {
            return world.getSpawnLocation();
        }
        double x = config.getDouble("lobby.spawn.x");
        double y = config.getDouble("lobby.spawn.y");
        double z = config.getDouble("lobby.spawn.z");
        double yaw = config.getDouble("lobby.spawn.yaw");
        double pitch = config.getDouble("lobby.spawn.pitch");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)
                || y < world.getMinHeight() || y >= world.getMaxHeight()
                || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) return world.getSpawnLocation();
        return new Location(world, x, y, z, (float) yaw, (float) Math.clamp(pitch, -90, 90));
    }

    private boolean protectedPlayer(Player player) {
        return plugin.isLobby(player) && !player.hasPermission("vlosses.bypass");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!teleportOnJoin || !plugin.isLobby(event.getPlayer())) return;
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (active && player != null && plugin.isLobby(player)) teleportSpawn(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!plugin.isLobby(event.getPlayer())) return;
        respawned.add(event.getPlayer().getUniqueId());
        if (!teleportOnRespawn) return;
        Location target = spawn();
        if (target != null) event.setRespawnLocation(target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (hunger && event.getEntity() instanceof Player player && protectedPlayer(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20);
            player.setExhaustion(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !protectedPlayer(player)) return;
        boolean voidDamage = event.getCause() == EntityDamageEvent.DamageCause.VOID;
        if (damage || fall && event.getCause() == EntityDamageEvent.DamageCause.FALL || voidProtection && voidDamage) {
            event.setCancelled(true);
            player.setFallDistance(0);
        }
        if (voidProtection && voidDamage) teleportSpawn(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blockBreak && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blockPlace && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blockPlace && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blockBreak && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (blockPlace && event.getPlayer() != null && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (fireSpread && plugin.isLobby(event.getBlock().getWorld())) { event.setCancelled(true); return; }
        if (blockPlace && event.getPlayer() != null && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (mobSpawning && plugin.isLobby(event.getLocation().getWorld())
                && !(event.getEntity() instanceof org.bukkit.entity.ArmorStand)
                && !(event.getEntity() instanceof Player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (explosions && plugin.isLobby(event.getLocation().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(org.bukkit.event.block.BlockExplodeEvent event) {
        if (explosions && plugin.isLobby(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(org.bukkit.event.entity.ExplosionPrimeEvent event) {
        if (explosions && plugin.isLobby(event.getEntity().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        if (fireSpread && plugin.isLobby(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        if (fireSpread && plugin.isLobby(event.getBlock().getWorld())
                && (event.getSource().getType() == org.bukkit.Material.FIRE || event.getSource().getType() == org.bukkit.Material.SOUL_FIRE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBlock(org.bukkit.event.entity.EntityChangeBlockEvent event) {
        if (entityGriefing && plugin.isLobby(event.getBlock().getWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (blockBreak && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!protectedPlayer(event.getPlayer())) return;
        if (interact) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        } else if (event.getClickedBlock() != null) {
            String block = event.getClickedBlock().getType().name();
            if (blockBreak && event.getAction() == org.bukkit.event.block.Action.PHYSICAL
                    && (block.equals("FARMLAND") || block.equals("TURTLE_EGG"))) {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
            if ((blockBreak || blockPlace) && event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                    && event.getItem() != null) {
                String held = event.getItem().getType().name();
                if (held.endsWith("_AXE") || held.endsWith("_HOE") || held.endsWith("_SHOVEL")
                        || held.equals("HONEYCOMB") || held.equals("SHEARS")) {
                    event.setUseItemInHand(Event.Result.DENY);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (interact && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (interact && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (interact && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (blockPlace && event.getPlayer() != null && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!interact) return;
        Entity attacker = event.getDamager();
        Player player = attacker instanceof Player source ? source
                : attacker instanceof Projectile projectile && projectile.getShooter() instanceof Player source ? source : null;
        if (player != null && protectedPlayer(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (blockBreak && event.getRemover() instanceof Player player && protectedPlayer(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (dropItems && protectedPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @Override
    public void close() {
        active = false;
        HandlerList.unregisterAll(this);
        for (UUID id : Set.copyOf(environments.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) leave(player);
        }
        environments.clear();
        teleporting.clear();
        respawned.clear();
        nextWarnings.clear();
    }

    private record Environment(long time, boolean relative, WeatherType weather) {}
}
