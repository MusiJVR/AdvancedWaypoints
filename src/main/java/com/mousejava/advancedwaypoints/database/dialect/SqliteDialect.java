package com.mousejava.advancedwaypoints.database.dialect;

import java.util.List;

public final class SqliteDialect implements SqlDialect {
    @Override
    public List<String> playersSchema() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS aw_players (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    nickname TEXT NOT NULL,
                    waypoint_limit INTEGER NOT NULL DEFAULT 3,
                    personal_color TEXT DEFAULT NULL,
                    CHECK (waypoint_limit >= 0)
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_aw_players_nickname ON aw_players (nickname)"
        );
    }

    @Override
    public String playersRegister() {
        return """
                INSERT INTO aw_players (uuid, nickname) VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET nickname = ?
                """;
    }

    @Override
    public String playersFindAll() {
        return "SELECT * FROM aw_players ORDER BY nickname, uuid";
    }

    @Override
    public String playersUpdateLimit() {
        return "UPDATE aw_players SET waypoint_limit = ? WHERE uuid = ?";
    }

    @Override
    public String playersUpdateColor() {
        return "UPDATE aw_players SET personal_color = ? WHERE uuid = ?";
    }

    @Override
    public String playersResetColor() {
        return "UPDATE aw_players SET personal_color = NULL WHERE uuid = ?";
    }

    @Override
    public String playersSelectWaypointLimit() {
        return "SELECT waypoint_limit FROM aw_players WHERE uuid = ?";
    }

    @Override
    public String playersSelectUuid() {
        return "SELECT uuid FROM aw_players WHERE uuid = ?";
    }

    @Override
    public List<String> waypointsSchema() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS aw_waypoints (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    owner_uuid TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    style TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (owner_uuid) REFERENCES aw_players(uuid)
                )
                """);
    }

    @Override
    public List<String> waypointVisibilitySchema() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS aw_waypoint_visibility (
                    waypoint_uuid TEXT NOT NULL PRIMARY KEY,
                    player_uuids TEXT NOT NULL,
                    FOREIGN KEY (waypoint_uuid) REFERENCES aw_waypoints(uuid) ON DELETE CASCADE
                )
                """);
    }

    @Override
    public String waypointsFindAll() {
        return """
                SELECT w.*, v.player_uuids
                FROM aw_waypoints w
                JOIN aw_waypoint_visibility v ON v.waypoint_uuid = w.uuid
                ORDER BY w.created_at, w.uuid
                """;
    }

    @Override
    public String waypointsCountByOwner() {
        return "SELECT COUNT(*) FROM aw_waypoints WHERE owner_uuid = ?";
    }

    @Override
    public String waypointsRegister() {
        return "INSERT INTO aw_waypoints (uuid, owner_uuid, world, x, y, z, style) VALUES (?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public String waypointVisibilityRegister() {
        return "INSERT INTO aw_waypoint_visibility (waypoint_uuid, player_uuids) VALUES (?, ?)";
    }

    @Override
    public String waypointsRemove() {
        return "DELETE FROM aw_waypoints WHERE uuid = ? AND owner_uuid = ?";
    }

    @Override
    public String waypointsAndPlayersSelect() {
        return """
                SELECT w.*, v.player_uuids FROM aw_waypoints w
                JOIN aw_waypoint_visibility v ON v.waypoint_uuid = w.uuid
                WHERE w.uuid = ? AND w.owner_uuid = ?
                """;
    }

    @Override
    public String waypointsUpdateStyle() {
        return "UPDATE aw_waypoints SET style = ? WHERE uuid = ?";
    }

    @Override
    public String waypointVisibilityUpdatePlayers() {
        return "UPDATE aw_waypoint_visibility SET player_uuids = ? WHERE waypoint_uuid = ?";
    }
}
