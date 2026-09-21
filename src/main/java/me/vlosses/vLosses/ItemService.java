package me.vlosses.vLosses;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ItemService implements Listener, AutoCloseable {
    private final VLosses plugin;
    private final NamespacedKey marker;
    private final NamespacedKey owner;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Long> nextWarnings = new HashMap<>();
    private boolean active;
    private boolean enabled;
    private boolean locked;
    private long cooldownNanos;

    public ItemService(VLosses plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "lobby_item");
        owner = new NamespacedKey(plugin, "lobby_item_owner");
    }

    public void start() {
        if (active) return;
        var config = plugin.itemsConfig();
        enabled = config.getBoolean("enabled", true);
        locked = config.getBoolean("lock", true);
        cooldownNanos = TimeUnit.MILLISECONDS.toNanos(Math.clamp(config.getLong("cooldown-ms", 750), 0, 60_000));
        ConfigurationSection entries = config.getConfigurationSection("items");
        if (enabled && entries != null) {
            boolean[] occupied = new boolean[9];
            for (String id : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(id);
                if (entry == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
                    warning(id, "invalid item identifier or section");
                    continue;
                }
                int slot = entry.getInt("slot", -1);
                Material material = Material.matchMaterial(entry.getString("material", "COMPASS"));
                if (slot < 0 || slot > 8 || occupied[slot] || material == null || !material.isItem() || material.isAir()) {
                    warning(id, "invalid material, duplicate slot or slot outside 0-8");
                    continue;
                }
                List<ItemAction> actions = parseActions(id, entry.getStringList("actions"));
                if (actions.isEmpty()) {
                    warning(id, "no valid actions; item disabled");
                    continue;
                }
                occupied[slot] = true;
                definitions.put(id, new Definition(id, slot, material, entry.getString("name", id),
                        List.copyOf(entry.getStringList("lore")), entry.getString("permission", "").trim(), actions));
            }
        }
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private List<ItemAction> parseActions(String id, List<String> configured) {
        List<ItemAction> result = new ArrayList<>();
        for (String action : configured) {
            int separator = action.indexOf(':');
            String typeName = (separator < 0 ? action : action.substring(0, separator)).trim().toUpperCase(Locale.ROOT);
            String value = separator < 0 ? "" : action.substring(separator + 1).trim();
            try {
                ActionType type = ActionType.valueOf(typeName);
                if (type != ActionType.SPAWN && value.isBlank()) throw new IllegalArgumentException();
                if (type == ActionType.SERVER && !value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
                if ((type == ActionType.PLAYER_COMMAND || type == ActionType.CONSOLE_COMMAND)
                        && (stripSlash(value).isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)) {
                    throw new IllegalArgumentException();
                }
                result.add(new ItemAction(type, value));
            } catch (IllegalArgumentException exception) {
                warning(id, "unsupported or invalid action " + typeName);
            }
        }
        return List.copyOf(result);
    }

    private void warning(String id, String reason) {
        plugin.getLogger().warning("items.yml: items." + id + ": " + reason);
    }

    public void give(Player player) {
        if (!active || !enabled || !player.isOnline() || player.isDead() || !plugin.isLobby(player)) return;
        ItemStack[] previous = player.getInventory().getContents();
        ItemStack[] contents = previous.clone();
        for (int slot = 0; slot < contents.length; slot++) {
            if (marked(contents[slot])) contents[slot] = null;
        }
        if (marked(player.getItemOnCursor())) player.setItemOnCursor(null);
        Definition conflict = null;
        for (Definition definition : definitions.values()) {
            if (!allowed(player, definition)) continue;
            ItemStack existing = contents[definition.slot()];
            if (existing != null && !existing.getType().isAir()) {
                if (conflict == null) conflict = definition;
                continue;
            }
            contents[definition.slot()] = create(player, definition);
        }
        if (!Arrays.equals(previous, contents)) player.getInventory().setContents(contents);
        if (conflict != null) {
            long now = System.nanoTime();
            Long next = nextWarnings.get(player.getUniqueId());
            if (next == null || now - next >= 0) {
                nextWarnings.put(player.getUniqueId(), now + TimeUnit.SECONDS.toNanos(30));
                plugin.tell(player, "items-slot-occupied", Map.of("slot", Integer.toString(conflict.slot() + 1), "item", conflict.id()));
            }
        }
    }

    private ItemStack create(Player player, Definition definition) {
        ItemStack item = new ItemStack(definition.material());
        var meta = item.getItemMeta();
        meta.displayName(plugin.text(player, definition.name()));
        meta.lore(definition.lore().stream().map(line -> plugin.text(player, line)).toList());
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, definition.id());
        meta.getPersistentDataContainer().set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    public void remove(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            if (marked(contents[slot])) {
                contents[slot] = null;
                changed = true;
            }
        }
        if (changed) player.getInventory().setContents(contents);
        if (marked(player.getItemOnCursor())) player.setItemOnCursor(null);
        cooldowns.remove(player.getUniqueId());
        nextWarnings.remove(player.getUniqueId());
    }

    private boolean marked(ItemStack item) {
        return item != null && !item.getType().isAir()
                && item.getPersistentDataContainer().has(marker, PersistentDataType.STRING);
    }

    private boolean allowed(Player player, Definition definition) {
        return definition.permission().isBlank() || player.hasPermission(definition.permission());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean current = marked(event.getCurrentItem());
        boolean cursor = marked(event.getCursor());
        boolean hotbar = event.getHotbarButton() >= 0 && marked(player.getInventory().getItem(event.getHotbarButton()));
        boolean offhand = event.getClick() == ClickType.SWAP_OFFHAND && marked(player.getInventory().getItemInOffHand());
        if (!current && !cursor && !hotbar && !offhand) return;
        InventoryAction action = event.getAction();
        boolean unsafe = locked || event instanceof InventoryCreativeEvent || offhand
                || !(event.getClickedInventory() instanceof PlayerInventory)
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.DROP_ALL_CURSOR || action == InventoryAction.DROP_ONE_CURSOR
                || action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT
                || action == InventoryAction.CLONE_STACK || action == InventoryAction.UNKNOWN;
        if (unsafe) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!marked(event.getOldCursor()) && event.getNewItems().values().stream().noneMatch(this::marked)) return;
        if (locked || event.getWhoClicked().getGameMode() == org.bukkit.GameMode.CREATIVE
                || event.getRawSlots().stream().anyMatch(slot -> !(event.getView().getInventory(slot) instanceof PlayerInventory))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (marked(event.getMainHandItem()) || marked(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (marked(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (marked(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (marked(event.getPlayer().getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityInteractAt(PlayerInteractAtEntityEvent event) {
        if (marked(event.getPlayer().getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (marked(event.getPlayerItem()) || marked(event.getArmorStandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && event.getHand() != null && marked(player.getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!marked(event.getItem().getItemStack())) return;
        event.setCancelled(true);
        event.getItem().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (!marked(event.getItem().getItemStack())) return;
        event.setCancelled(true);
        event.getItem().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (marked(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::marked);
        event.getItemsToKeep().removeIf(this::marked);
        remove(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (!marked(event.getItem())) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!enabled || !plugin.isLobby(player)) return;
        var data = event.getItem().getPersistentDataContainer();
        Definition definition = definitions.get(data.get(marker, PersistentDataType.STRING));
        if (definition == null || !player.getUniqueId().toString().equals(data.get(owner, PersistentDataType.STRING))) return;
        if (!allowed(player, definition)) {
            plugin.tell(player, "no-permission");
            return;
        }
        long now = System.nanoTime();
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Long previous = playerCooldowns.get(definition.id());
        if (previous != null && now - previous < cooldownNanos) return;
        playerCooldowns.put(definition.id(), now);
        for (ItemAction action : definition.actions()) {
            if (!player.isOnline() || !plugin.isLobby(player) || !allowed(player, definition)) break;
            execute(player, action);
        }
    }

    private void execute(Player player, ItemAction action) {
        if ((action.type() == ActionType.PLAYER_COMMAND || action.type() == ActionType.CONSOLE_COMMAND)
                && !player.getName().matches("[A-Za-z0-9_.-]{1,32}")) {
            plugin.warn("items.yml: command action skipped because the player name is not a safe command token");
            return;
        }
        String value = action.value().replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString());
        switch (action.type()) {
            case MESSAGE -> player.sendMessage(plugin.text(player, value));
            case PLAYER_COMMAND -> player.performCommand(stripSlash(value));
            case CONSOLE_COMMAND -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(value));
            case SERVER -> plugin.bridge().connect(player, value);
            case SPAWN -> plugin.teleportSpawn(player);
        }
    }

    private static String stripSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    @Override
    public void close() {
        active = false;
        HandlerList.unregisterAll(this);
        for (Player player : Bukkit.getOnlinePlayers()) remove(player);
        definitions.clear();
        cooldowns.clear();
        nextWarnings.clear();
    }

    private enum ActionType { MESSAGE, PLAYER_COMMAND, CONSOLE_COMMAND, SERVER, SPAWN }
    private record ItemAction(ActionType type, String value) {}
    private record Definition(String id, int slot, Material material, String name, List<String> lore,
                              String permission, List<ItemAction> actions) {}
}
