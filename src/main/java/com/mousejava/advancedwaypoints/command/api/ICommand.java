package com.mousejava.advancedwaypoints.command.api;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Set;

public interface ICommand {
    LiteralCommandNode<CommandSourceStack> create();

    default String description() {
        return "";
    }

    default Set<String> aliases() {
        return Set.of();
    }
}
