package com.mousejava.advancedwaypoints.database.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mousejava.advancedwaypoints.database.api.DatabaseManager;
import com.mousejava.advancedwaypoints.database.api.SchemaRepository;
import com.mousejava.advancedwaypoints.database.dialect.SqlDialect;
import com.mousejava.advancedwaypoints.model.Waypoint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public final class WaypointsRepository implements SchemaRepository {
    private static final Gson GSON = new Gson();
    private final DatabaseManager<SqlDialect> database;

    public WaypointsRepository(DatabaseManager<SqlDialect> database) {
        this.database = database;
    }

    @Override
    public void initializeSchema() {
        database.dialect().waypointsSchema().forEach(database::execute);
        database.dialect().waypointVisibilitySchema().forEach(database::execute);
    }

    public List<Waypoint> findAll() {
        return database.query(database.dialect().waypointsFindAll(), this::map);
    }

    private Waypoint map(ResultSet row) throws SQLException {
        return new Waypoint(UUID.fromString(row.getString("uuid")), UUID.fromString(row.getString("owner_uuid")),
                row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"), row.getString("style"),
                GSON.fromJson(row.getString("player_uuids"), new TypeToken<Set<UUID>>() {}.getType()));
    }

    public boolean create(Waypoint waypoint) {
        return database.transaction(connection -> {
            int limit;

            try (PreparedStatement statement = connection.prepareStatement(database.dialect().playersSelectWaypointLimit())) {
                statement.setString(1, waypoint.owner().toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next())
                        throw new IllegalStateException("Waypoint owner is not registered");

                    limit = rows.getInt(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(database.dialect().waypointsCountByOwner())) {
                statement.setString(1, waypoint.owner().toString());
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= limit)
                        return false;
                }
            }

            execute(connection, database.dialect().waypointsRegister(),
                    waypoint.uuid().toString(), waypoint.owner().toString(), waypoint.world(), waypoint.x(), waypoint.y(), waypoint.z(), waypoint.style());

            Set<UUID> viewers = new HashSet<>(waypoint.viewers());
            viewers.add(waypoint.owner());

            execute(connection, database.dialect().waypointVisibilityRegister(),
                    waypoint.uuid().toString(), GSON.toJson(viewers));

            return true;
        });
    }

    public boolean remove(UUID uuid, UUID owner) {
        return database.transaction(connection -> execute(connection,
                database.dialect().waypointsRemove(), uuid.toString(), owner.toString()) > 0);
    }

    public Optional<Waypoint> modify(UUID uuid, UUID owner, String action, String value) {
        return database.transaction(connection -> {
            Waypoint waypoint;
            try (PreparedStatement statement = connection.prepareStatement(database.dialect().waypointsAndPlayersSelect())) {
                statement.setString(1, uuid.toString());
                statement.setString(2, owner.toString());

                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next())
                        return Optional.empty();

                    waypoint = map(rows);
                }
            }

            Set<UUID> viewers = new HashSet<>(waypoint.viewers());
            String style = waypoint.style();
            switch (action) {
                case "style" -> {
                    style = value;
                    execute(connection, database.dialect().waypointsUpdateStyle(), style, uuid.toString());
                }
                case "add", "remove" -> {
                    UUID viewer = UUID.fromString(value);
                    if (action.equals("add")) {
                        try (PreparedStatement statement = connection.prepareStatement(database.dialect().playersSelectUuid())) {
                            statement.setString(1, viewer.toString());
                            try (ResultSet rows = statement.executeQuery()) {
                                if (!rows.next())
                                    return Optional.empty();
                            }
                        }
                        viewers.add(viewer);
                    } else {
                        if (viewer.equals(owner))
                            return Optional.empty();
                        viewers.remove(viewer);
                    }

                    viewers.add(owner);
                    execute(connection, database.dialect().waypointVisibilityUpdatePlayers(),
                            GSON.toJson(viewers), uuid.toString());
                }
                default -> throw new IllegalArgumentException("Unknown waypoint action");
            }

            return Optional.of(new Waypoint(uuid, owner, waypoint.world(), waypoint.x(), waypoint.y(), waypoint.z(), style, viewers));
        });
    }

    private int execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }
}
