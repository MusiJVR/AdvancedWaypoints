package com.mousejava.advancedwaypoints.service;

import com.mousejava.advancedwaypoints.database.repository.WaypointsRepository;
import com.mousejava.advancedwaypoints.model.Waypoint;
import com.mousejava.advancedwaypoints.util.Scheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.waypoints.WaypointStyleAssets;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class WaypointsService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final WaypointsRepository waypointsRepository;
    private final PlayersService playersService;
    private final Map<UUID, Map<UUID, Waypoint>> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, Scheduler.Task> tasks = new ConcurrentHashMap<>();
    private volatile List<Waypoint> waypoints = List.of();
    private volatile List<String> styles = List.of();
    private volatile boolean closed;

    public WaypointsService(JavaPlugin plugin, WaypointsRepository waypointsRepository, PlayersService playersService) {
        this.plugin = plugin;
        this.waypointsRepository = waypointsRepository;
        this.playersService = playersService;
    }

    public void start() {
        reloadStyles();
        waypoints = List.copyOf(waypointsRepository.findAll());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::track);
    }

    public void reloadStyles() {
        styles = plugin.getConfig().getStringList("waypoint_styles").stream().filter(style -> {
            boolean valid = style.contains(":") && Identifier.tryParse(style) != null && style.length() <= 255;
            if (!valid)
                plugin.getLogger().warning("Invalid waypoint style: " + style);

            return valid;
        }).distinct().toList();
    }

    public CompletableFuture<Void> reload() {
        reloadStyles();
        return playersService.submit(() -> {
            waypoints = List.copyOf(waypointsRepository.findAll());
            return null;
        });
    }

    public List<String> getStyles() {
        return styles;
    }

    public List<Waypoint> list(UUID player) {
        return waypoints.stream().filter(waypoint -> waypoint.viewers().contains(player)).toList();
    }

    public CompletableFuture<Optional<Waypoint>> create(Player player, String style) {
        if (!styles.contains(style))
            throw new IllegalArgumentException("Unknown waypoint style");

        Location location = player.getLocation();
        UUID owner = player.getUniqueId();
        String name = player.getName();
        Waypoint waypoint = new Waypoint(UUID.randomUUID(), owner, location.getWorld().getKey().toString(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), style, Set.of(owner));

        return playersService.register(owner, name).thenCompose(ignored -> playersService.submit(() -> {
            if (!waypointsRepository.create(waypoint))
                return Optional.empty();

            Map<UUID, Waypoint> updated = new LinkedHashMap<>();
            waypoints.forEach(value -> updated.put(value.uuid(), value));
            updated.put(waypoint.uuid(), waypoint);
            waypoints = List.copyOf(updated.values());
            return Optional.of(waypoint);
        }));
    }

    public CompletableFuture<Boolean> remove(UUID uuid, UUID owner) {
        return playersService.submit(() -> {
            boolean removed = waypointsRepository.remove(uuid, owner);
            if (removed)
                waypoints = waypoints.stream().filter(waypoint -> !waypoint.uuid().equals(uuid)).toList();

            return removed;
        });
    }

    public List<Waypoint> owned(UUID owner) {
        return waypoints.stream().filter(waypoint -> waypoint.owner().equals(owner)).toList();
    }

    public CompletableFuture<Optional<Waypoint>> modify(UUID uuid, UUID owner, String action, String value) {
        if (action.equals("style") && !styles.contains(value))
            throw new IllegalArgumentException("Unknown waypoint style");

        return playersService.submit(() -> {
            Optional<Waypoint> modified = waypointsRepository.modify(uuid, owner, action, value);
            modified.ifPresent(updated -> waypoints = waypoints.stream()
                    .map(waypoint -> waypoint.uuid().equals(uuid) ? updated : waypoint).toList());
            return modified;
        });
    }

    private void track(Player player) {
        Scheduler.Task previous = tasks.put(player.getUniqueId(), Scheduler.runForEntityTimer(player, () -> refresh(player), 1, 20));
        if (previous != null)
            previous.cancel();
    }

    public void refresh(Player player) {
        if (closed || !player.isOnline())
            return;

        Map<UUID, Waypoint> sent = tracked.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());
        Map<UUID, Waypoint> visible = new LinkedHashMap<>();
        String world = player.getWorld().getKey().toString();

        list(player.getUniqueId()).stream().filter(waypoint -> waypoint.world().equals(world))
                .forEach(waypoint -> visible.put(waypoint.uuid(), waypoint));

        sent.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(visible.get(entry.getKey())))
                return false;

            send(player, ClientboundTrackedWaypointPacket.removeWaypoint(entry.getKey()));
            return true;
        });

        visible.forEach((uuid, waypoint) -> {
            if (sent.containsKey(uuid))
                return;

            net.minecraft.world.waypoints.Waypoint.Icon icon = new net.minecraft.world.waypoints.Waypoint.Icon();
            Identifier identifier = Identifier.tryParse(waypoint.style());
            if (identifier == null)
                return;

            icon.style = ResourceKey.create(WaypointStyleAssets.ROOT_ID, identifier);
            icon.color = Optional.of(0xFFFFFF);
            send(player, ClientboundTrackedWaypointPacket.addWaypointPosition(uuid, icon, new BlockPos(waypoint.x(), waypoint.y(), waypoint.z())));
            sent.put(uuid, waypoint);
        });
    }

    private void reset(Player player) {
        Map<UUID, Waypoint> sent = tracked.remove(player.getUniqueId());
        if (sent != null)
            sent.keySet().forEach(uuid -> send(player, ClientboundTrackedWaypointPacket.removeWaypoint(uuid)));

        Scheduler.runForEntityLater(player, () -> refresh(player), 1);
    }

    private void send(Player player, ClientboundTrackedWaypointPacket packet) {
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Scheduler.Task task = tasks.remove(uuid);

        if (task != null)
            task.cancel();

        tracked.remove(uuid);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        reset(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        reset(event.getPlayer());
    }

    @Override
    public void close() {
        closed = true;
        tasks.values().forEach(Scheduler.Task::cancel);
        tasks.clear();
        
        Bukkit.getOnlinePlayers().forEach(player -> {
            Map<UUID, Waypoint> sent = tracked.get(player.getUniqueId());
            if (sent != null)
                sent.keySet().forEach(uuid -> send(player, ClientboundTrackedWaypointPacket.removeWaypoint(uuid)));
        });

        tracked.clear();
    }
}

