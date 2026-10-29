package com.mousejava.advancedwaypoints.model;

import java.util.Set;
import java.util.UUID;

public record Waypoint(UUID uuid, UUID owner, String world, int x, int y, int z, String style, Set<UUID> viewers) {
    public Waypoint {
        viewers = Set.copyOf(viewers);
    }
}
