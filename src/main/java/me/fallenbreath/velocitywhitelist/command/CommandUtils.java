package me.fallenbreath.velocitywhitelist.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.CommandSource;

// Utility class containing helper methods for command building and suggestion matching
public class CommandUtils {

    // Creates a literal argument builder for the specified string literal
    public static LiteralArgumentBuilder<CommandSource> literal(
        final String name
    ) {
        return LiteralArgumentBuilder.literal(name);
    }

    // Creates a required argument builder with the specified name and argument type
    public static <T> RequiredArgumentBuilder<CommandSource, T> argument(
        final String name,
        final ArgumentType<T> type
    ) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    // Filters and provides completion suggestions that match the currently typed input
    public static CompletableFuture<Suggestions> suggestMatching(
        Iterable<String> suggestions,
        SuggestionsBuilder suggestionsBuilder
    ) {
        String remaining = suggestionsBuilder
            .getRemaining()
            .toLowerCase(Locale.ROOT);

        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                suggestionsBuilder.suggest(suggestion);
            }
        }

        return suggestionsBuilder.buildFuture();
    }
}
