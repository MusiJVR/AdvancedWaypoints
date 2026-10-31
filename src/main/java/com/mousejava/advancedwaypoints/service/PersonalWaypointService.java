package com.mousejava.advancedwaypoints.service;

import com.mousejava.advancedwaypoints.util.Scheduler;
import net.minecraft.util.ARGB;
import net.minecraft.world.waypoints.Waypoint;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PersonalWaypointService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PlayersService playersService;
    private final Map<UUID, Scheduler.Task> tasks = new ConcurrentHashMap<>();

    public PersonalWaypointService(JavaPlugin plugin, PlayersService playersService) {
        this.plugin = plugin;
        this.playersService = playersService;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::track);
    }

    public CompletableFuture<Void> setColor(Player player, String color) {
        return playersService.setColor(player.getUniqueId(), player.getName(), color);
    }

    public CompletableFuture<Void> resetColor(Player player) {
        return playersService.resetColor(player.getUniqueId(), player.getName());
    }

    public String getCurrentColor(Player player) {
        var profile = playersService.find(player.getUniqueId()).orElseThrow();
        if (profile.personalColor() != null)
            return profile.personalColor();

        int color = new Waypoint.Icon().cloneAndAssignStyle(((CraftPlayer) player).getHandle()).color
                .orElseGet(() -> ARGB.setBrightness(ARGB.color(255, player.getUniqueId().hashCode()), 0.9F));
        return String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    public void apply(Player player) {
        if (!player.isOnline())
            return;

        playersService.find(player.getUniqueId()).ifPresent(profile -> {
            Color color = profile.personalColor() == null ? null : Color.fromRGB(Integer.parseInt(profile.personalColor(), 16));
            if (!Objects.equals(color, player.getWaypointColor()))
                player.setWaypointColor(color);
        });
    }

    private void track(Player player) {
        playersService.register(player.getUniqueId(), player.getName()).whenComplete((ignored, error) -> {
            if (error != null)
                plugin.getLogger().log(Level.SEVERE, "Failed to register waypoint player", error);
        });

        Scheduler.Task previous = tasks.put(player.getUniqueId(), Scheduler.runForEntityTimer(player, () -> apply(player), 1, 20));
        if (previous != null)
            previous.cancel();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Scheduler.Task task = tasks.remove(event.getPlayer().getUniqueId());
        if (task != null)
            task.cancel();
    }

    @Override
    public void close() {
        tasks.values().forEach(Scheduler.Task::cancel);
        tasks.clear();
    }
}
