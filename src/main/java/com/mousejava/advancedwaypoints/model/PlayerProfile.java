package com.mousejava.advancedwaypoints.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record PlayerProfile(UUID uuid, String nickname, int waypointLimit, @Nullable String personalColor) {
}
