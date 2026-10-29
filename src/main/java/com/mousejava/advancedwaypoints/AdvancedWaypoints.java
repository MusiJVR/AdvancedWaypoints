package com.mousejava.advancedwaypoints;

import com.mousejava.advancedwaypoints.command.WaypointsCommand;
import com.mousejava.advancedwaypoints.command.api.ICommand;
import com.mousejava.advancedwaypoints.database.api.DatabaseConfig;
import com.mousejava.advancedwaypoints.database.api.DatabaseManager;
import com.mousejava.advancedwaypoints.database.api.SchemaRepository;
import com.mousejava.advancedwaypoints.database.dialect.*;
import com.mousejava.advancedwaypoints.database.repository.*;
import com.mousejava.advancedwaypoints.listener.UpdateNotifyListener;
import com.mousejava.advancedwaypoints.service.*;
import com.mousejava.advancedwaypoints.utils.MessageUtils;
import com.mousejava.advancedwaypoints.utils.Scheduler;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class AdvancedWaypoints extends JavaPlugin {
    private static final int SERVICE_ID = 35003;
    private static final String PROJECT_ID = "advancedwaypoints";

    private DatabaseManager<SqlDialect> database;

    private PlayersRepository playersRepository;
    private WaypointsRepository waypointsRepository;
    private PlayersService playersService;
    private WaypointsService waypointsService;
    private PersonalWaypointService personalWaypointService;
    private ResourcePackService resourcePackService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Scheduler.init(this);
        MessageUtils.init(this);
        BStatsMetricsService.init(this, SERVICE_ID);
        UpdateCheckerService.init(this, PROJECT_ID);

        database = initializeDatabase();

        waypointsRepository = new WaypointsRepository(database);
        playersRepository = new PlayersRepository(database);

        List.of(
                playersRepository,
                waypointsRepository
        ).forEach(SchemaRepository::initializeSchema);

        playersService = new PlayersService(playersRepository);
        personalWaypointService = new PersonalWaypointService(this, playersService);
        waypointsService = new WaypointsService(this, waypointsRepository, playersService);
        resourcePackService = new ResourcePackService(this, PROJECT_ID);

        playersService.load();
        personalWaypointService.start();
        waypointsService.start();

        registerListeners();
        registerCommands();
    }

    @Override
    public void onDisable() {
        if (waypointsService != null)
            waypointsService.close();

        if (personalWaypointService != null)
            personalWaypointService.close();

        if (playersService != null)
            playersService.close();

        if (database != null && database.isRunning())
            database.close();
    }

    private DatabaseManager<SqlDialect> initializeDatabase() {
        DatabaseConfig config = DatabaseConfig.from(getConfig(), getDataFolder());
        SqlDialect dialect = switch (config.type()) {
            case SQLITE -> new SqliteDialect();
            case MYSQL -> new MySqlDialect();
        };

        return new DatabaseManager<>(getName() + "Pool", config, dialect);
    }

    private void registerListeners() {
        List<Listener> listeners = List.of(
                new UpdateNotifyListener(this)
        );

        PluginManager pluginManager = getServer().getPluginManager();
        listeners.forEach(listener -> pluginManager.registerEvents(listener, this));
    }

    private void registerCommands() {
        List<ICommand> commands = List.of(
                new WaypointsCommand(this, waypointsService, playersService, personalWaypointService, resourcePackService)
        );

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            commands.forEach(command -> registrar.register(command.create(), command.description(), command.aliases()));
        });
    }
}
