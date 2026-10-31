package com.mousejava.advancedwaypoints.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mousejava.advancedwaypoints.command.api.Cmd;
import com.mousejava.advancedwaypoints.command.api.ICommand;
import com.mousejava.advancedwaypoints.model.PlayerProfile;
import com.mousejava.advancedwaypoints.model.Waypoint;
import com.mousejava.advancedwaypoints.service.PersonalWaypointService;
import com.mousejava.advancedwaypoints.service.PlayersService;
import com.mousejava.advancedwaypoints.service.ResourcePackService;
import com.mousejava.advancedwaypoints.service.WaypointsService;
import com.mousejava.advancedwaypoints.util.MessageUtil;
import com.mousejava.advancedwaypoints.util.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class WaypointsCommand implements ICommand {
    private static final int PAGE_SIZE = 5;
    private final JavaPlugin plugin;
    private final WaypointsService waypointsService;
    private final PlayersService playersService;
    private final PersonalWaypointService personalWaypointService;
    private final ResourcePackService resourcePackService;

    public WaypointsCommand(JavaPlugin plugin, WaypointsService waypointsService, PlayersService playersService, PersonalWaypointService personalWaypointService, ResourcePackService resourcePackService) {
        this.plugin = plugin;
        this.waypointsService = waypointsService;
        this.playersService = playersService;
        this.personalWaypointService = personalWaypointService;
        this.resourcePackService = resourcePackService;
    }

    @Override
    public LiteralCommandNode<CommandSourceStack> create() {
        return Cmd.senderCommand("point", "advancedwaypoints.use", "messages.point.usage")
                .then(buildHelp())
                .then(buildReload())
                .then(buildResourcePack())
                .then(buildLimit())
                .then(buildPersonal())
                .then(buildCreate())
                .then(buildRemove())
                .then(buildList())
                .then(buildModify())
                .build();
    }

    @Override
    public String description() {
        return "This command allows you to manage waypoints";
    }

    @Override
    public Set<String> aliases() {
        return Set.copyOf(plugin.getConfig().getStringList("command_aliases"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildHelp() {
        return Cmd.senderCommand("help", "advancedwaypoints.help", this::executeHelp);
    }

    private int executeHelp(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        if (sender instanceof Player) {
            MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.help");
        } else {
            String prefix = plugin.getPluginMeta().getLoggerPrefix();
            MessageUtil.sendMiniMessageTransformed(sender, "messages.point.help",
                    msg -> Arrays.stream(msg.split("\n"))
                            .map(line -> "[%s] %s".formatted(prefix, line))
                            .collect(Collectors.joining("\n"))
            );
        }

        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildReload() {
        return Cmd.senderCommand("reload", "advancedwaypoints.reload", this::executeReload);
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        plugin.reloadConfig();
        complete(sender, CompletableFuture.allOf(waypointsService.reload(), playersService.reload()), ignored ->
                MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.reload.reload_successfully"));
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildResourcePack() {
        return Cmd.senderCommand("resourcepack", "advancedwaypoints.resourcepack", "messages.point.resourcepack.usage")
                .then(Cmd.senderCommand("generate", this::executeResourcePackGenerate))
                .then(Cmd.senderCommand("build", this::executeResourcePackBuild));
    }

    private int executeResourcePackGenerate(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        try {
            resourcePackService.generateDefaultAssets();
            MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.resourcepack.generate_success");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to generate default resource pack assets", exception);
            MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.resourcepack.processing_failed");
        }

        return Command.SINGLE_SUCCESS;
    }

    private int executeResourcePackBuild(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        try {
            resourcePackService.buildResourcePack();
            MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.resourcepack.build_success");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to build resource pack", exception);
            MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.resourcepack.processing_failed");
        }

        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildLimit() {
        return Cmd.senderCommand("limit", "advancedwaypoints.limit", "messages.point.limit.usage")
                .then(Cmd.argument("player", StringArgumentType.word(), playerSuggestions(false))
                        .executes(ctx -> Cmd.usage(ctx, "messages.point.limit.usage"))
                        .then(Cmd.executesSender(Cmd.argument("limit", IntegerArgumentType.integer(0)), this::executeLimit)));
    }

    private int executeLimit(CommandContext<CommandSourceStack> ctx, CommandSender sender) {
        PlayerProfile profile = playersService.find(StringArgumentType.getString(ctx, "player")).orElse(null);
        if (profile == null)
            return Cmd.usage(sender, "messages.point.player_not_found");

        int limit = IntegerArgumentType.getInteger(ctx, "limit");
        complete(sender, playersService.setLimit(profile.uuid(), limit), ignored ->
                MessageUtil.sendMiniMessageTransformed(sender, "messages.point.limit.limit_updated", text -> text,
                        TagResolver.resolver(Placeholder.unparsed("player", profile.nickname()),
                                Placeholder.unparsed("limit", Integer.toString(limit)))));

        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildPersonal() {
        return Cmd.playerCommand("personal", "advancedwaypoints.personal", "messages.point.personal.usage")
                .then(Cmd.playerCommand("info", this::executePersonalInfo))
                .then(Cmd.literal("color").executes(ctx -> Cmd.usage(ctx, "messages.point.personal.usage"))
                        .then(Cmd.executesPlayer(Cmd.argument("color", StringArgumentType.word()), this::executePersonalColor)))
                .then(Cmd.literal("reset").executes(ctx -> Cmd.usage(ctx, "messages.point.personal.usage"))
                        .then(Cmd.playerCommand("color", this::executePersonalResetColor)));
    }

    private int executePersonalInfo(CommandContext<CommandSourceStack> ctx, Player player) {
        complete(player, playersService.register(player.getUniqueId(), player.getName()), ignored ->
                MessageUtil.sendMiniMessageTransformed(player, "messages.point.personal.info", text -> text
                        .replaceAll("<hex>", personalWaypointService.getCurrentColor(player))));
        return Command.SINGLE_SUCCESS;
    }

    private int executePersonalColor(CommandContext<CommandSourceStack> ctx, Player player) {
        String color = StringArgumentType.getString(ctx, "color");

        if (!color.matches("[0-9a-fA-F]{6}"))
            return Cmd.usage(player, "messages.point.personal.invalid_color");

        complete(player, personalWaypointService.setColor(player, color), ignored -> {
            personalWaypointService.apply(player);
            MessageUtil.sendMiniMessageTransformed(player, "messages.point.personal.color_updated", text -> text
                    .replaceAll("<hex>", color.toUpperCase(Locale.ROOT)));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int executePersonalResetColor(CommandContext<CommandSourceStack> ctx, Player player) {
        complete(player, personalWaypointService.resetColor(player), ignored -> {
            personalWaypointService.apply(player);
            MessageUtil.sendMiniMessageIfPresent(player, "messages.point.personal.reset.color");
        });

        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildCreate() {
        return Cmd.playerCommand("create", "advancedwaypoints.create", "messages.point.create.usage")
                .then(Cmd.executesPlayer(Cmd.argument("style", StringArgumentType.greedyString(), (ctx, builder) -> {
                    waypointsService.getStyles().stream().filter(style -> style.startsWith(builder.getRemaining()))
                            .forEach(builder::suggest);
                    return builder.buildFuture();
                }), this::executeCreate));
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx, Player player) {
        String style = StringArgumentType.getString(ctx, "style");
        if (!waypointsService.getStyles().contains(style))
            return Cmd.usage(player, "messages.point.create.unknown_style");

        complete(player, waypointsService.create(player, style), waypoint -> {
            waypointsService.refresh(player);
            if (waypoint.isPresent())
                sendWaypoint(player, "messages.point.create.created_successfully", waypoint.get());
            else
                MessageUtil.sendMiniMessageIfPresent(player, "messages.point.create.limit_reached");
        });

        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildRemove() {
        return Cmd.playerCommand("remove", "advancedwaypoints.remove", "messages.point.remove.usage")
                .then(Cmd.executesPlayer(Cmd.argument("uuid", StringArgumentType.word(), (ctx, builder) -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        waypointsService.list(player.getUniqueId()).stream()
                                .filter(waypoint -> waypoint.owner().equals(player.getUniqueId()))
                                .map(waypoint -> waypoint.uuid().toString())
                                .filter(uuid -> uuid.startsWith(builder.getRemaining())).forEach(builder::suggest);
                    }
                    return builder.buildFuture();
                }), this::executeRemove));
    }

    private int executeRemove(CommandContext<CommandSourceStack> ctx, Player player) {
        UUID uuid;
        try {
            String input = StringArgumentType.getString(ctx, "uuid");
            uuid = UUID.fromString(input);
            if (!uuid.toString().equalsIgnoreCase(input))
                throw new IllegalArgumentException("Invalid UUID");
        } catch (IllegalArgumentException exception) {
            return Cmd.usage(player, "messages.point.remove.invalid_uuid");
        }

        complete(player, waypointsService.remove(uuid, player.getUniqueId()), removed -> {
            waypointsService.refresh(player);
            MessageUtil.sendMiniMessageIfPresent(player, removed
                    ? "messages.point.remove.removed_successfully" : "messages.point.remove.not_found");
        });

        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildList() {
        return Cmd.playerCommand("list", "advancedwaypoints.list", (ctx, player) -> executeList(player, 1, false))
                .then(Cmd.executesPlayer(Cmd.argument("page", IntegerArgumentType.integer()),
                        (ctx, player) -> executeList(player, IntegerArgumentType.getInteger(ctx, "page"), true)));
    }

    private int executeList(Player player, int page, boolean explicitPage) {
        List<Waypoint> waypoints = waypointsService.list(player.getUniqueId());
        if (waypoints.isEmpty() && !explicitPage)
            return Cmd.usage(player, "messages.point.list.empty");

        int pages = waypoints.size() / PAGE_SIZE + (waypoints.size() % PAGE_SIZE == 0 ? 0 : 1);
        if (page < 1 || page > pages)
            return Cmd.usage(player, "messages.point.list.page_not_found");

        MessageUtil.sendMiniMessageTransformed(player, "messages.point.list.header", text -> text,
                Placeholder.unparsed("count", Integer.toString(waypoints.size())));

        int start = (page - 1) * PAGE_SIZE;
        int end = start + Math.min(PAGE_SIZE, waypoints.size() - start);
        waypoints.subList(start, end).forEach(waypoint -> sendWaypoint(player, "messages.point.list.entry", waypoint));

        if (pages > 1)
            MessageUtil.sendMiniMessageTransformed(player, "messages.point.list.pagination", text -> text, TagResolver.resolver(
                    Placeholder.component("previous", pageArrow("previous", page - 1, page > 1)),
                    Placeholder.component("next", pageArrow("next", page + 1, page < pages)),
                    Placeholder.unparsed("page", Integer.toString(page)),
                    Placeholder.unparsed("pages", Integer.toString(pages))
            ));

        return Command.SINGLE_SUCCESS;
    }

    private Component pageArrow(String direction, int page, boolean enabled) {
        Component arrow = MessageUtil.optionalMiniMessage("messages.point.list." + direction + (enabled ? "" : "_disabled"))
                .orElse(Component.empty());
        return arrow.clickEvent(enabled ? ClickEvent.runCommand("/point list " + page) : null);
    }

    private SuggestionProvider<CommandSourceStack> ownedSuggestions() {
        return (ctx, builder) -> {
            if (ctx.getSource().getSender() instanceof Player player)
                waypointsService.owned(player.getUniqueId()).stream().map(waypoint -> waypoint.uuid().toString())
                        .filter(uuid -> uuid.startsWith(builder.getRemaining())).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> playerSuggestions(boolean viewersOnly) {
        return (ctx, builder) -> {
            List<PlayerProfile> profiles = playersService.list();
            if (viewersOnly) {
                UUID uuid = parseUuid(StringArgumentType.getString(ctx, "uuid"));
                if (!(ctx.getSource().getSender() instanceof Player player) || uuid == null)
                    return builder.buildFuture();

                Waypoint waypoint = waypointsService.owned(player.getUniqueId()).stream()
                        .filter(value -> value.uuid().equals(uuid)).findFirst().orElse(null);

                if (waypoint == null)
                    return builder.buildFuture();

                profiles = profiles.stream().filter(profile -> waypoint.viewers().contains(profile.uuid())
                        && !waypoint.owner().equals(profile.uuid())).toList();
            }

            profiles.stream().map(PlayerProfile::nickname).distinct()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase()))
                    .forEach(builder::suggest);

            return builder.buildFuture();
        };
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildModify() {
        RequiredArgumentBuilder<CommandSourceStack, String> uuid = Cmd.argument("uuid", StringArgumentType.word(), ownedSuggestions())
                .executes(ctx -> Cmd.usage(ctx, "messages.point.modify.usage"));

        for (String action : List.of("style", "add", "remove")) {
            SuggestionProvider<CommandSourceStack> suggestions = action.equals("style")
                    ? (ctx, builder) -> {
                        waypointsService.getStyles().stream().filter(style -> style.startsWith(builder.getRemaining()))
                                .forEach(builder::suggest);
                        return builder.buildFuture();
                    } : playerSuggestions(action.equals("remove"));

            uuid.then(Cmd.literal(action).executes(ctx -> Cmd.usage(ctx, "messages.point.modify.usage"))
                    .then(Cmd.executesPlayer(Cmd.argument("value", StringArgumentType.greedyString(), suggestions),
                            (ctx, player) -> executeModify(ctx, player, action))));
        }

        return Cmd.playerCommand("modify", "advancedwaypoints.modify", "messages.point.modify.usage").then(uuid);
    }

    private int executeModify(CommandContext<CommandSourceStack> ctx, Player player, String action) {
        UUID uuid = parseUuid(StringArgumentType.getString(ctx, "uuid"));
        if (uuid == null)
            return Cmd.usage(player, "messages.point.remove.invalid_uuid");

        Waypoint waypoint = waypointsService.owned(player.getUniqueId()).stream()
                .filter(value -> value.uuid().equals(uuid)).findFirst().orElse(null);

        if (waypoint == null)
            return Cmd.usage(player, "messages.point.remove.not_found");

        String value = StringArgumentType.getString(ctx, "value");
        if (action.equals("style")) {
            if (!waypointsService.getStyles().contains(value))
                return Cmd.usage(player, "messages.point.create.unknown_style");
        } else {
            PlayerProfile profile = playersService.find(value).orElse(null);
            if (profile == null)
                return Cmd.usage(player, "messages.point.player_not_found");

            if (action.equals("remove") && profile.uuid().equals(player.getUniqueId()))
                return Cmd.usage(player, "messages.point.modify.owner_required");

            value = profile.uuid().toString();
        }

        complete(player, waypointsService.modify(uuid, player.getUniqueId(), action, value), modified -> {
            waypointsService.refresh(player);
            MessageUtil.sendMiniMessageIfPresent(player, modified.isPresent()
                    ? "messages.point.modify.modified_successfully" : "messages.point.remove.not_found");
        });
        return Command.SINGLE_SUCCESS;
    }

    private UUID parseUuid(String input) {
        try {
            UUID uuid = UUID.fromString(input);
            return uuid.toString().equalsIgnoreCase(input) ? uuid : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void sendWaypoint(Player player, String path, Waypoint waypoint) {
        Component uuid = Component.text(waypoint.uuid().toString())
                .clickEvent(ClickEvent.copyToClipboard(waypoint.uuid().toString()));

        uuid = uuid.hoverEvent(MessageUtil.optionalMiniMessage("messages.point.copy_uuid").orElse(Component.empty()));

        MessageUtil.sendMiniMessageTransformed(player, path, text -> text, TagResolver.resolver(
                Placeholder.component("uuid", uuid),
                Placeholder.unparsed("style", waypoint.style()),
                Placeholder.unparsed("world", waypoint.world()),
                Placeholder.unparsed("x", Integer.toString(waypoint.x())),
                Placeholder.unparsed("y", Integer.toString(waypoint.y())),
                Placeholder.unparsed("z", Integer.toString(waypoint.z()))
        ));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> action) {
        future.whenComplete((result, error) -> {
            if (error != null)
                plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint command", error);

            if (!plugin.isEnabled())
                return;

            Runnable callback = () -> {
                if (error != null)
                    MessageUtil.sendMiniMessageIfPresent(sender, "messages.point.command_failed");
                else
                    action.accept(result);
            };

            if (sender instanceof Player player)
                Scheduler.runForEntity(player, callback);
            else
                Scheduler.run(callback);
        });
    }
}
