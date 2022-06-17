package me.dreamerzero.kickredirect;

import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.plugin.PluginManager;

import me.dreamerzero.kickredirect.listener.DebugListener;
import me.dreamerzero.kickredirect.listener.KickListener;
import me.dreamerzero.kickredirect.listener.objects.TestContinuation;
import me.dreamerzero.kickredirect.listener.objects.TestProxyServer;
import net.kyori.adventure.builder.AbstractBuilder;

public final class EventBundle {
    private final TestContinuation continuation;
    private final Player player;
    private final KickedFromServerEvent event;
    private final ProxyServer proxyServer;
    private final KickRedirect plugin;

    private EventBundle(final Builder builder) {
        this.proxyServer = new TestProxyServer();
        this.plugin = new KickRedirect(
            this.proxyServer,
            Path.of("build", "reports", "tests", "test"),
            LoggerFactory.getLogger(EventBundle.class),
            this.proxyServer.getPluginManager()
        );
        plugin.initialize(true);
        if(builder.debug)
            plugin.config().get().debug(true);
        this.continuation = new TestContinuation();
        this.player = builder.player;
        this.event = builder.event;
    }

    public static Builder builder() {
        return new Builder();
    }

    public KickRedirect getPlugin() {
        return plugin;
    }

    public PluginManager getPluginManager() {
        return proxyServer.getPluginManager();
    }

    public TestContinuation getContinuation() {
        return continuation;
    }

    public Player getPlayer() {
        return player;
    }

    public KickedFromServerEvent getEvent() {
        return event;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public void applyListener() {
        final KickListener listener = new KickListener(plugin);
        listener.onKickFromServer(event, continuation);
    }

    public EventTask applyDebug() {
        final DebugListener listener = new DebugListener(plugin);
        return listener.afterKickFromServer(event);
    }

    public static class Builder implements AbstractBuilder<EventBundle> {
        private Player player;
        private KickedFromServerEvent event;
        private boolean debug;

        private Builder() {}

        public Builder player(Player player) {
            this.player = player;
            return this;
        }

        public Builder event(EventBuilder event) {
            this.event = event.player(this.player).build();
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        @Override
        public @NotNull EventBundle build() {
            return new EventBundle(this);
        }
    }
}
