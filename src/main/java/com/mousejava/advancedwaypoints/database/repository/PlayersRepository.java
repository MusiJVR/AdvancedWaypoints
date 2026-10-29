package com.mousejava.advancedwaypoints.database.repository;

import com.mousejava.advancedwaypoints.database.api.DatabaseManager;
import com.mousejava.advancedwaypoints.database.api.SchemaRepository;
import com.mousejava.advancedwaypoints.database.dialect.SqlDialect;
import com.mousejava.advancedwaypoints.model.PlayerProfile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PlayersRepository implements SchemaRepository {
    private final DatabaseManager<SqlDialect> database;

    public PlayersRepository(DatabaseManager<SqlDialect> database) {
        this.database = database;
    }

    @Override
    public void initializeSchema() {
        database.dialect().playersSchema().forEach(database::execute);
    }

    public void register(UUID uuid, String nickname) {
        database.execute(database.dialect().playersRegister(), uuid.toString(), nickname, nickname);
    }

    public List<PlayerProfile> findAll() {
        return database.query(database.dialect().playersFindAll(), row -> new PlayerProfile(
                UUID.fromString(row.getString("uuid")), row.getString("nickname"),
                row.getInt("waypoint_limit"), row.getString("personal_color")));
    }

    public void setLimit(UUID uuid, int limit) {
        if (limit < 0)
            throw new IllegalArgumentException("Waypoint limit cannot be negative");

        database.execute(database.dialect().playersUpdateLimit(), limit, uuid.toString());
    }

    public void setColor(UUID uuid, String color) {
        if (!color.matches("[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("Expected a six-digit HEX color");

        database.execute(database.dialect().playersUpdateColor(), color.toUpperCase(Locale.ROOT), uuid.toString());
    }

    public void resetColor(UUID uuid) {
        database.execute(database.dialect().playersResetColor(), uuid.toString());
    }
}
