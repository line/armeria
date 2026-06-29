/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.xds.server;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ConnectionAcceptor;
import com.linecorp.armeria.server.ConnectionContext;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPlugin;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServerTlsProvider;
import com.linecorp.armeria.server.ServerTlsSpec;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.xds.FilterChainSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.internal.DelegatingHttpService;

import io.netty.util.AttributeKey;

/**
 * A {@link ServerPlugin} that applies xDS-based configuration to an Armeria {@link Server}.
 *
 * <p>On each incoming connection, the plugin performs
 * <a href="https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/listener/v3/listener_components.proto#config-listener-v3-filterchainmatch">
 * filter chain matching</a> to select the appropriate TLS certificate and HTTP filter chain.
 * Per-request route matching then delegates to the xDS-configured HTTP filters and routes.
 * Once a request goes through HTTP filters, user-defined {@link HttpService}s are invoked.
 *
 * <p>Only the ports explicitly specified when creating this plugin are managed by xDS
 * configuration. The listener's port in the xDS config does not influence which ports are managed.
 *
 * <p>During {@link #install(ServerBuilder)}, this plugin blocks until the first xDS snapshot
 * is resolved so that TLS certificates and filter chains are available before the server starts
 * accepting connections. The timeout can be configured via
 * {@link XdsServerPluginBuilder#readyTimeout(java.time.Duration)} (default: 30 seconds).
 *
 * <p>Example usage:
 * <pre>{@code
 * Server.builder()
 *     .plugin(XdsServerPlugin.of(xdsBootstrap, "listener", 8080, 8443))
 *     .service("/api", myService)
 *     .build();
 * }</pre>
 */
@UnstableApi
public final class XdsServerPlugin implements ServerPlugin {

    private static final AttributeKey<FilterChainSnapshot> MATCHED_FILTER_CHAIN =
            AttributeKey.valueOf(XdsServerPlugin.class, "MATCHED_FILTER_CHAIN");
    private static final UnmodifiableFuture<ServerTlsSpec> noTlsSpecFailure =
            UnmodifiableFuture.exceptionallyCompletedFuture(
                    new IllegalStateException("No matching ServerTlsSpec found."));

    private final ListenerRoot listenerRoot;
    private final ServerSnapshotWatcher watcher = new ServerSnapshotWatcher();
    private final List<ServerPort> serverPorts;
    private final Duration readyTimeout;

    /**
     * Creates a new {@link XdsServerPlugin} that subscribes to the given listener
     * and listens on the specified port(s) with HTTP and HTTPS.
     * If no ports are specified, an ephemeral port will be added and used.
     */
    public static XdsServerPlugin of(XdsBootstrap bootstrap, String listenerName, int... ports) {
        return builder(bootstrap, listenerName, ports).build();
    }

    /**
     * Returns a new {@link XdsServerPluginBuilder}.
     * If no ports are specified, an ephemeral port will be added and used.
     */
    public static XdsServerPluginBuilder builder(XdsBootstrap bootstrap, String listenerName,
                                                 int... ports) {
        return new XdsServerPluginBuilder(bootstrap, listenerName, ports);
    }

    XdsServerPlugin(XdsBootstrap bootstrap, String listenerName,
                    List<ServerPort> serverPorts, Duration readyTimeout) {
        listenerRoot = bootstrap.listenerRoot(listenerName);
        listenerRoot.addSnapshotWatcher(watcher);
        this.serverPorts = serverPorts;
        this.readyTimeout = readyTimeout;
    }

    @Override
    public void install(ServerBuilder sb) {
        try {
            watcher.whenReady().get(readyTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Exceptions.throwUnsafely(e);
            return;
        }
        for (ServerPort serverPort : serverPorts) {
            sb.port(serverPort);
        }
        final ConnectionAcceptor existingAcceptor = sb.connectionAcceptor();
        sb.connectionAcceptor(new XdsConnectionAcceptor(existingAcceptor, serverPorts, watcher));
        final ServerTlsProvider existingTlsProvider = sb.tlsProvider();
        sb.tlsProvider(new XdsServerTlsProvider(existingTlsProvider));
        sb.decorator(delegate -> new XdsRootDecorator(delegate, watcher));
    }

    @Override
    public void close() {
        listenerRoot.close();
    }

    private static final class XdsConnectionAcceptor implements ConnectionAcceptor {

        private final ConnectionAcceptor existingAcceptor;
        private final List<ServerPort> serverPorts;
        private final ServerSnapshotWatcher watcher;

        private XdsConnectionAcceptor(ConnectionAcceptor existingAcceptor,
                                      List<ServerPort> serverPorts,
                                      ServerSnapshotWatcher watcher) {
            this.existingAcceptor = existingAcceptor;
            this.serverPorts = serverPorts;
            this.watcher = watcher;
        }

        @Override
        public CompletableFuture<Boolean> accept(ConnectionContext ctx) {
            // Only apply xDS policy to connections on xDS-managed ports.
            // actualPort() resolves ephemeral ports (0) to the real bound port.
            final int port = ctx.localAddress().getPort();
            boolean managed = false;
            for (ServerPort sp : serverPorts) {
                if (sp.actualPort() == port) {
                    managed = true;
                    break;
                }
            }
            if (!managed) {
                return existingAcceptor.accept(ctx);
            }
            final FilterChainSnapshot matched = watcher.match(ctx);
            if (matched != null) {
                ctx.setAttr(MATCHED_FILTER_CHAIN, matched);
                return existingAcceptor.accept(ctx);
            }
            return UnmodifiableFuture.completedFuture(false);
        }
    }

    private static final class XdsServerTlsProvider implements ServerTlsProvider {

        @Nullable
        private final ServerTlsProvider existingTlsProvider;

        private XdsServerTlsProvider(@Nullable ServerTlsProvider existingTlsProvider) {
            this.existingTlsProvider = existingTlsProvider;
        }

        @Override
        public CompletableFuture<@Nullable ServerTlsSpec> serverTlsSpec(ConnectionContext ctx) {
            final FilterChainSnapshot matched = ctx.attr(MATCHED_FILTER_CHAIN);
            if (matched == null) {
                if (existingTlsProvider != null) {
                    return existingTlsProvider.serverTlsSpec(ctx);
                }
                return UnmodifiableFuture.completedFuture(null);
            } else {
                final ServerTlsSpec spec = matched.transportSocketSnapshot().serverTlsSpec(ctx);
                if (spec == null) {
                    return noTlsSpecFailure;
                }
                return UnmodifiableFuture.completedFuture(spec);
            }
        }
    }

    private static final class XdsRootDecorator extends SimpleDecoratingHttpService {

        private final ServerSnapshotWatcher watcher;

        XdsRootDecorator(HttpService delegate, ServerSnapshotWatcher watcher) {
            super(delegate);
            this.watcher = watcher;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            super.serviceAdded(cfg);
            watcher.serviceAdded(cfg);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final FilterChainSnapshot matched = ctx.attr(MATCHED_FILTER_CHAIN);
            if (matched == null) {
                // Not an xDS-managed port — serve directly.
                return unwrap().serve(ctx, req);
            }
            final RouteSnapshot routeSnapshot = matched.routeSnapshot();
            if (routeSnapshot == null) {
                // HCM with routes is required for all filter chains.
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                                       "HCM with routes is required for all filter chains");
            }
            final RouteEntry entry = routeSnapshot.select(ctx);
            if (entry == null) {
                // No matching virtual host or route.
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                                       "No matching virtual host or route");
            }
            DelegatingHttpService.setDelegate(ctx, (HttpService) unwrap());
            return entry.httpService().serve(ctx, req);
        }
    }
}
