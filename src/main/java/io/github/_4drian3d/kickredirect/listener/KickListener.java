package io.github._4drian3d.kickredirect.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.inject.Inject;
import com.velocitypowered.api.event.AwaitingEventExecutor;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.ServerKickResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github._4drian3d.kickredirect.KickRedirect;
import io.github._4drian3d.kickredirect.configuration.Configuration;
import io.github._4drian3d.kickredirect.configuration.ConfigurationContainer;
import io.github._4drian3d.kickredirect.configuration.Messages;
import io.github._4drian3d.kickredirect.enums.CheckMode;
import io.github._4drian3d.kickredirect.enums.KickStep;
import io.github._4drian3d.kickredirect.formatter.Formatter;
import io.github._4drian3d.kickredirect.modules.KickRedirectSource;
import io.github._4drian3d.kickredirect.utils.DebugInfo;
import io.github._4drian3d.kickredirect.utils.Registrable;
import io.github._4drian3d.kickredirect.utils.Strings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class KickListener implements AwaitingEventExecutor<KickedFromServerEvent>, Registrable {
    @Inject
    private KickRedirect plugin;
    @Inject
    private ProxyServer proxyServer;
    @Inject
    private EventManager eventManager;
    @Inject
    private Formatter formatter;
    @Inject
    private ConfigurationContainer<Configuration> configurationContainer;
    @Inject
    private ConfigurationContainer<Messages> messagesContainer;
    @Inject
    private Cache<UUID, DebugInfo> debugCache;
    @Inject
    private KickRedirectSource source;
    private final Cache<UUID, String> sent = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(10))
            .build();

    @Override
    public EventTask executeAsync(final KickedFromServerEvent event) {
        return EventTask.withContinuation(continuation -> {
            final Player player = event.getPlayer();
            final RegisteredServer server = event.getServer();
            final Configuration configuration = configurationContainer.get();
            if(shouldSkipServer(server, configuration)){
                player.disconnect(Component.text("Disconnected"));
                return;
            }
            if (shouldKick(player, server)) {
                continuation.resume();
                cache(event, server.getServerInfo().getName(), KickStep.REPEATED_ATTEMPT);
                // This should keep the "original" DisconnectPlayer result
                return;
            }
            if (reasonCheck(event)) {
                final RegisteredServer server2Redirect2 = configuration
                        .getSendMode()
                        .server(
                                proxyServer,
                                configuration.getServersToRedirect(),
                                configuration.getRandomAttempts()
                        );
                if (server2Redirect2 == null) {
                    source.sendMessage(formatter.format(
                            messagesContainer.get().noServersFoundToRedirect(),
                            player,
                            Placeholder.unparsed(
                                    "sendmode",
                                    configuration.getSendMode().toString()
                            )
                    ));
                    applyKickResult(event);
                    continuation.resume();
                    cache(event, null, KickStep.NULL_SERVER);
                } else {
                    event.setResult(redirectResult(server2Redirect2, player));
                    continuation.resume();
                    cache(event, server2Redirect2.getServerInfo().getName(), KickStep.AVAILABLE_SERVER);
                    addToSent(player, server2Redirect2);
                }
            } else {
                continuation.resume();
                cache(event, null, KickStep.DISALLOWED_REASON);
            }
        });
    }

    // Cache event information in case debug mode is enabled
    void cache(final KickedFromServerEvent event, final String serverName, final KickStep step) {
        if (configurationContainer.get().debug()) {
            debugCache.put(event.getPlayer().getUniqueId(), new DebugInfo(event, serverName, step));
        }
    }

    // Check in case the kick message corresponds
    // to a message configured for revision
    boolean reasonCheck(final KickedFromServerEvent event) {
        final Optional<String> optional = event.getServerKickReason()
                .map(PlainTextComponentSerializer.plainText()::serialize);

        final Configuration configuration = configurationContainer.get();

        if (optional.isPresent()) {
            final String message = optional.get();
            for (final String msg : configuration.getMessagesToCheck()) {
                if (Strings.containsIgnoreCase(message, msg)) {
                    return configuration.checkMode() == CheckMode.WHITELIST;
                }
            }
            return configuration.checkMode() != CheckMode.WHITELIST;
        } else {
            return configuration.redirectOnNullMessage();
        }
    }

    // If the redirection message is empty,
    // simply apply a redirection result without message
    ServerKickResult redirectResult(final RegisteredServer server, final Player player) {
        final String redirectMessage = messagesContainer.get().redirectMessage();
        if (redirectMessage.isBlank()) {
            return KickedFromServerEvent.RedirectPlayer.create(server);
        } else {
            final Component message = formatter.format(redirectMessage, player);
            return KickedFromServerEvent.RedirectPlayer.create(server, message);
        }
    }

    // If the configured kick message is empty,
    // it avoids applying a new result,
    // since Velocity already applies the kick result
    void applyKickResult(final KickedFromServerEvent event) {
        final String kickMessage = messagesContainer.get().kickMessage();
        if (!kickMessage.isBlank()) {
            event.setResult(
                KickedFromServerEvent.DisconnectPlayer.create(
                    formatter.format(kickMessage, event.getPlayer())
                )
            );
        }
    }

    // The server from which the player has been expelled is added to the cache,
    // to avoid infinite redirection calculations
    // https://github.com/4drian3d/KickRedirect/issues/5
    void addToSent(final Player player, final RegisteredServer server) {
        sent.put(player.getUniqueId(), server.getServerInfo().getName());
    }

    // If the player has already been kicked from the same server recently,
    // do not retry to recalculate the server to redirect
    // https://github.com/4drian3d/KickRedirect/issues/5
    boolean shouldKick(final Player player, final RegisteredServer server) {
        return Objects.equals(sent.getIfPresent(player.getUniqueId()), server.getServerInfo().getName());
    }

    boolean shouldSkipServer(final RegisteredServer server, Configuration configuration) {
        return configuration.getServersToNotRedirectFrom().contains(server.getServerInfo().getName());
    }

    @Override
    public void register() {
        eventManager.register(
            plugin,
            KickedFromServerEvent.class,
            configurationContainer.get().getListenerPriority(),
            this
        );
    }
}
