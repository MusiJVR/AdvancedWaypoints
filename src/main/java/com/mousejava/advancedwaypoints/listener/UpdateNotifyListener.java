package com.mousejava.advancedwaypoints.listener;

import com.mousejava.advancedwaypoints.service.UpdateCheckerService;
import com.mousejava.advancedwaypoints.utils.MessageUtils;
import com.mousejava.advancedwaypoints.utils.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class UpdateNotifyListener implements Listener {
    private final JavaPlugin plugin;

    public UpdateNotifyListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("send_update_notifications", true))
            return;

        Player player = event.getPlayer();
        if (!player.hasPermission("advancedwaypoints.update_listener"))
            return;

        if (!UpdateCheckerService.isUpdateAvailable())
            return;

        Scheduler.runForEntityLater(player, () -> sendUpdateMessage(player), 40);
    }

    private void sendUpdateMessage(Player player) {
        String current = plugin.getPluginMeta().getVersion();
        String latest = UpdateCheckerService.getLatestVersion();
        String url = UpdateCheckerService.getDownloadUrl();

        MessageUtils.sendMiniMessageTransformed(player, "messages.new_version_available",
                msg -> msg
                        .replace("<current_version>", current)
                        .replace("<new_version>", latest)
                        .replace("<url>", url)
        );
    }
}
