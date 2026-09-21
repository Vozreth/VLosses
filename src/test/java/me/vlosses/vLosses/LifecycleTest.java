package me.vlosses.vLosses;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleTest {
    private ServerMock server;
    private VLosses plugin;

    @BeforeEach void start() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(VLosses.class);
        assertTrue(plugin.isEnabled());
    }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void mobSpawnBlockedForEveryReasonOnlyInLobby() {
        var survival = server.addSimpleWorld("survival");
        var mob = survival.spawn(survival.getSpawnLocation(), org.bukkit.entity.Zombie.class);
        for (var reason : org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.values()) {
            var outside = new org.bukkit.event.entity.CreatureSpawnEvent(mob, reason);
            server.getPluginManager().callEvent(outside);
            assertFalse(outside.isCancelled(), reason.name());
        }
        mob.teleport(server.getWorld("world").getSpawnLocation());
        for (var reason : org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.values()) {
            var inside = new org.bukkit.event.entity.CreatureSpawnEvent(mob, reason);
            server.getPluginManager().callEvent(inside);
            assertTrue(inside.isCancelled(), reason.name());
        }
    }

    @Test void fireProtectionIsScopedAndReloadCanDisableMobProtection() {
        var world = server.getWorld("world");
        var fire = new org.bukkit.event.block.BlockIgniteEvent(world.getBlockAt(0, 65, 0),
                org.bukkit.event.block.BlockIgniteEvent.IgniteCause.LAVA, (org.bukkit.entity.Entity) null);
        server.getPluginManager().callEvent(fire);
        assertTrue(fire.isCancelled());
        var other = server.addSimpleWorld("survival");
        fire = new org.bukkit.event.block.BlockIgniteEvent(other.getBlockAt(0, 65, 0),
                org.bukkit.event.block.BlockIgniteEvent.IgniteCause.LAVA, (org.bukkit.entity.Entity) null);
        server.getPluginManager().callEvent(fire);
        assertFalse(fire.isCancelled());
        plugin.getConfig().set("lobby.protection.mob-spawning", false);
        plugin.saveConfig();
        server.dispatchCommand(server.getConsoleSender(), "vl reload");
        var mob = other.spawn(other.getSpawnLocation(), org.bukkit.entity.Zombie.class);
        mob.teleport(world.getSpawnLocation());
        var spawn = new org.bukkit.event.entity.CreatureSpawnEvent(mob, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
        server.getPluginManager().callEvent(spawn);
        assertFalse(spawn.isCancelled());
    }

    @Test void directCommandsHaveCorrectRoutingAndPermissions() {
        var player = server.addPlayer();
        player.addAttachment(plugin, "vlosses.spawn", false);
        while (player.nextMessage() != null) { }
        assertTrue(server.dispatchCommand(player, "lobby"));
        assertNotNull(player.nextMessage());
        for (String label : java.util.List.of("discord", "discor", "dc", "vote", "oy", "website", "site")) {
            while (player.nextMessage() != null) { }
            assertTrue(server.dispatchCommand(player, label));
            String response = player.nextMessage();
            assertNotNull(response, label);
            assertFalse(response.contains("/vl spawn"), label);
        }
        assertEquals("lobby", server.getPluginCommand("lobby").getName());
        assertEquals("discord", server.getPluginCommand("discor").getName());
        assertEquals(java.util.List.of(), plugin.onTabComplete(player, plugin.getCommand("lobby"), "lobby", new String[]{""}));
    }

    @Test void joinGivesItemsWithoutOverwritingExistingInventory() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(4, new ItemStack(Material.DIAMOND, 7));
        server.getScheduler().performTicks(2);
        assertEquals(Material.COMPASS, player.getInventory().getItem(0).getType());
        assertEquals(7, player.getInventory().getItem(4).getAmount());
        assertEquals(Material.DIAMOND, player.getInventory().getItem(4).getType());
    }
    @Test void protectionsAreScopedToLobbyAndExplicitBypass() {
        PlayerMock player = server.addPlayer();
        var block = player.getWorld().getBlockAt(0, 65, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        player.addAttachment(plugin, "vlosses.bypass", true);
        event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled());
    }
    @Test void localeRefreshDoesNotHealAndLeavingRemovesOnlyOwnedItems() {
        PlayerMock player = server.addPlayer();
        server.getScheduler().performTicks(2);
        player.setHealth(8);
        plugin.setLanguage(player.getUniqueId(), "de", false);
        server.getScheduler().performTicks(2);
        assertEquals(8, player.getHealth());
        player.getInventory().setItem(2, new ItemStack(Material.DIAMOND, 3));
        var previous = player.getWorld();
        player.setLocation(server.addSimpleWorld("survival").getSpawnLocation());
        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, previous));
        server.getScheduler().performTicks(2);
        assertNull(player.getInventory().getItem(0));
        assertEquals(3, player.getInventory().getItem(2).getAmount());
        FoodLevelChangeEvent food = new FoodLevelChangeEvent(player, 10);
        server.getPluginManager().callEvent(food);
        assertFalse(food.isCancelled());
    }
    @Test void invalidReloadKeepsPreviousSettingsAndPluginEnabled() throws Exception {
        java.nio.file.Files.writeString(plugin.getDataFolder().toPath().resolve("config.yml"), "lobby: [broken");
        server.dispatchCommand(server.getConsoleSender(), "vl reload");
        assertTrue(plugin.isEnabled());
        assertEquals(java.util.List.of("world"), plugin.getConfig().getStringList("lobby.worlds"));
    }

    @Test void reloadPreservesOrdinaryItemsAndDoesNotDuplicateLobbyItems() {
        PlayerMock player = server.addPlayer();
        server.getScheduler().performTicks(2);
        player.getInventory().setItem(2, new ItemStack(Material.DIAMOND, 3));
        server.dispatchCommand(server.getConsoleSender(), "vl reload");
        assertTrue(plugin.isEnabled());
        assertEquals(3, player.getInventory().getItem(2).getAmount());
        assertEquals(1, player.getInventory().getItem(0).getAmount());
        long count = java.util.Arrays.stream(player.getInventory().getContents())
                .filter(java.util.Objects::nonNull).filter(item -> item.getType() == Material.COMPASS).count();
        assertEquals(1, count);
    }

    @Test void dropAndShiftClickCannotExportLobbyItems() {
        PlayerMock player = server.addPlayer();
        server.getScheduler().performTicks(2);
        var dropped = player.getWorld().dropItem(player.getLocation(), player.getInventory().getItem(0));
        var drop = new org.bukkit.event.player.PlayerDropItemEvent(player, dropped);
        server.getPluginManager().callEvent(drop);
        assertTrue(drop.isCancelled());
        player.openInventory(server.createInventory(null, 27));
        var click = new org.bukkit.event.inventory.InventoryClickEvent(player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.QUICKBAR, 54,
                org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY);
        click.setCurrentItem(player.getInventory().getItem(0));
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled());
    }

    @Test void disableRemovesManagedItemsButKeepsPlayerInventory() {
        PlayerMock player = server.addPlayer();
        server.getScheduler().performTicks(2);
        player.getInventory().setItem(2, new ItemStack(Material.DIAMOND, 3));
        server.getPluginManager().disablePlugin(plugin);
        assertNull(player.getInventory().getItem(0));
        assertEquals(3, player.getInventory().getItem(2).getAmount());
        assertFalse(plugin.isEnabled());
    }
}
