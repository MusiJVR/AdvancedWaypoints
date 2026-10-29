package com.mousejava.advancedwaypoints.service;

import com.mousejava.advancedwaypoints.database.repository.PlayersRepository;
import com.mousejava.advancedwaypoints.model.PlayerProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class PlayersService implements AutoCloseable {
    private final PlayersRepository playersRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile List<PlayerProfile> players = List.of();

    public PlayersService(PlayersRepository playersRepository) {
        this.playersRepository = playersRepository;
    }

    public void load() {
        players = List.copyOf(playersRepository.findAll());
    }

    public CompletableFuture<Void> reload() {
        return submit(() -> {
            load();
            return null;
        });
    }

    public List<PlayerProfile> list() {
        return players;
    }

    public Optional<PlayerProfile> find(UUID uuid) {
        return players.stream().filter(player -> player.uuid().equals(uuid)).findFirst();
    }

    public Optional<PlayerProfile> find(String nickname) {
        List<PlayerProfile> matches = players.stream().filter(player -> player.nickname().equalsIgnoreCase(nickname)).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public CompletableFuture<Void> register(UUID uuid, String nickname) {
        return submit(() -> {
            playersRepository.register(uuid, nickname);
            load();
            return null;
        });
    }

    public CompletableFuture<Void> setLimit(UUID uuid, int limit) {
        return submit(() -> {
            playersRepository.setLimit(uuid, limit);
            load();
            return null;
        });
    }

    public CompletableFuture<Void> setColor(UUID uuid, String nickname, String color) {
        return submit(() -> {
            playersRepository.register(uuid, nickname);
            playersRepository.setColor(uuid, color);
            load();
            return null;
        });
    }

    public CompletableFuture<Void> resetColor(UUID uuid, String nickname) {
        return submit(() -> {
            playersRepository.register(uuid, nickname);
            playersRepository.resetColor(uuid);
            load();
            return null;
        });
    }

    public <T> CompletableFuture<T> submit(Supplier<T> operation) {
        return CompletableFuture.supplyAsync(operation, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS))
                executor.shutdownNow();
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
