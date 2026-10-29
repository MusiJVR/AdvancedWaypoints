package com.mousejava.advancedwaypoints.database.dialect;

import java.util.List;

public interface SqlDialect {
    List<String> playersSchema();
    String playersRegister();
    String playersFindAll();
    String playersUpdateLimit();
    String playersUpdateColor();
    String playersResetColor();
    String playersSelectWaypointLimit();
    String playersSelectUuid();
    List<String> waypointsSchema();
    List<String> waypointVisibilitySchema();
    String waypointsFindAll();
    String waypointsCountByOwner();
    String waypointsRegister();
    String waypointVisibilityRegister();
    String waypointsRemove();
    String waypointsAndPlayersSelect();
    String waypointsUpdateStyle();
    String waypointVisibilityUpdatePlayers();
}
