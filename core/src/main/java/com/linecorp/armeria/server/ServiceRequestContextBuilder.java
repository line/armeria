/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.AbstractRequestContextBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;

/**
 * Builds a new {@link ServiceRequestContext}. Note that it is not usually required to create a new context by
 * yourself, because Armeria will always provide a context object for you. However, it may be useful in some
 * cases such as unit testing.
 */
public final class ServiceRequestContextBuilder extends AbstractRequestContextBuilder {

    /**
     * A placeholder service to make {@link ServerBuilder} happy.
     */
    private static final HttpService fakeService = (ctx, req) -> HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);

    /**
     * A {@link ServerListener} which rejects an attempt to start a {@link Server}.
     */
    private static final ServerListener rejectingListener = new ServerListenerAdapter() {
        @Override
        public void serverStarting(Server server) throws Exception {
            throw new UnsupportedOperationException();
        }
    };

    /**
     * Returns a new {@link ServiceRequestContextBuilder} created from the specified {@link HttpRequest}.
     */
    public static ServiceRequestContextBuilder of(HttpRequest request) {
        return new ServiceRequestContextBuilder(request);
    }

    private final List<Consumer<? super ServerBuilder>> serverConfigurators = new ArrayList<>(4);

    private Service<HttpRequest, HttpResponse> service = fakeService;
    @Nullable
    private RoutingResult routingResult;
    @Nullable
    private ProxiedAddresses proxiedAddresses;
    @Nullable
    private InetAddress clientAddress;

    private ServiceRequestContextBuilder(HttpRequest request) {
        super(true, request);
    }

    /**
     * Sets the {@link Service} that handles the request. If not set, a dummy {@link Service}, which always
     * returns a {@code "405 Method Not Allowed"} response, is used.
     */
    public ServiceRequestContextBuilder service(Service<HttpRequest, HttpResponse> service) {
        this.service = requireNonNull(service, "service");
        return this;
    }

    /**
     * Sets the {@link RoutingResult} of the request. If not set, it is auto-generated from the request.
     */
    public ServiceRequestContextBuilder routingResult(RoutingResult routingResult) {
        this.routingResult = requireNonNull(routingResult, "routingResult");
        return this;
    }

    /**
     * Sets the {@link ProxiedAddresses} of the request.
     * If not set, {@link ServiceRequestContext#proxiedAddresses()} will return {@code null}.
     */
    public ServiceRequestContextBuilder proxiedAddresses(ProxiedAddresses proxiedAddresses) {
        this.proxiedAddresses = requireNonNull(proxiedAddresses, "proxiedAddresses");
        return this;
    }

    /**
     * Sets the client address of the request. If not set, {@link ServiceRequestContext#clientAddress()} will
     * return the same value as {@link ServiceRequestContext#remoteAddress()}.
     */
    public ServiceRequestContextBuilder clientAddress(InetAddress clientAddress) {
        this.clientAddress = requireNonNull(clientAddress, "clientAddress");
        return this;
    }

    /**
     * Adds the {@link Consumer} that configures the given {@link ServerBuilder}. The {@link Consumer}s added
     * by this method will be invoked when this builder builds a dummy {@link Server}. This may be useful
     * when you need to update the default settings of the dummy {@link Server},
     * such as {@link ServerConfig#maxRequestLength()}.
     */
    public ServiceRequestContextBuilder serverConfigurator(Consumer<? super ServerBuilder> serverConfigurator) {
        serverConfigurators.add(requireNonNull(serverConfigurator, "serverConfigurator"));
        return this;
    }

    /**
     * Returns a new {@link ServiceRequestContext} created with the properties of this builder.
     */
    public ServiceRequestContext build() {
        // Determine the client address; use remote address unless overridden.
        final InetAddress clientAddress;
        if (this.clientAddress != null) {
            clientAddress = this.clientAddress;
        } else {
            clientAddress = remoteAddress().getAddress();
        }

        // Build a fake server which never starts up.
        final ServerBuilder serverBuilder = Server.builder()
                                                  .meterRegistry(meterRegistry())
                                                  .workerGroup(eventLoop(), false)
                                                  .service(path(), service);
        serverConfigurators.forEach(configurator -> configurator.accept(serverBuilder));

        final Server server = serverBuilder.build();
        server.addListener(rejectingListener);

        // Retrieve the ServiceConfig of the fake service.
        final ServiceConfig serviceCfg = findServiceConfig(server, path(), service);

        // Build a fake object related with path mapping.
        final RoutingContext routingCtx = DefaultRoutingContext.of(
                server.config().defaultVirtualHost(),
                localAddress().getHostString(),
                path(),
                query(),
                ((HttpRequest) request()).headers(),
                false);

        final RoutingResult routingResult =
                this.routingResult != null ? this.routingResult
                                           : RoutingResult.builder().path(path()).query(query()).build();

        // Build the context with the properties set by a user and the fake objects.
        if (isRequestStartTimeSet()) {
            return new DefaultServiceRequestContext(
                    serviceCfg, fakeChannel(), meterRegistry(), sessionProtocol(), routingCtx,
                    routingResult, request(), sslSession(), proxiedAddresses, clientAddress,
                    requestStartTimeNanos(), requestStartTimeMicros());
        } else {
            return new DefaultServiceRequestContext(
                    serviceCfg, fakeChannel(), meterRegistry(), sessionProtocol(), routingCtx,
                    routingResult, request(), sslSession(), proxiedAddresses, clientAddress);
        }
    }

    private static ServiceConfig findServiceConfig(Server server, String path, Service<?, ?> service) {
        for (ServiceConfig cfg : server.config().defaultVirtualHost().serviceConfigs()) {
            final Route route = cfg.route();
            if (route.pathType() != RoutePathType.EXACT) {
                continue;
            }

            if (!path.equals(route.paths().get(0))) {
                continue;
            }

            if (cfg.service().as(service.getClass()).isPresent()) {
                return cfg;
            }
        }

        throw new Error(); // Never reaches here.
    }

    // Methods that were overridden to change the return type.

    @Override
    public ServiceRequestContextBuilder meterRegistry(MeterRegistry meterRegistry) {
        return (ServiceRequestContextBuilder) super.meterRegistry(meterRegistry);
    }

    @Override
    public ServiceRequestContextBuilder eventLoop(EventLoop eventLoop) {
        return (ServiceRequestContextBuilder) super.eventLoop(eventLoop);
    }

    @Override
    public ServiceRequestContextBuilder alloc(ByteBufAllocator alloc) {
        return (ServiceRequestContextBuilder) super.alloc(alloc);
    }

    @Override
    public ServiceRequestContextBuilder sessionProtocol(SessionProtocol sessionProtocol) {
        return (ServiceRequestContextBuilder) super.sessionProtocol(sessionProtocol);
    }

    @Override
    public ServiceRequestContextBuilder remoteAddress(InetSocketAddress remoteAddress) {
        return (ServiceRequestContextBuilder) super.remoteAddress(remoteAddress);
    }

    @Override
    public ServiceRequestContextBuilder localAddress(InetSocketAddress localAddress) {
        return (ServiceRequestContextBuilder) super.localAddress(localAddress);
    }

    @Override
    public ServiceRequestContextBuilder sslSession(SSLSession sslSession) {
        return (ServiceRequestContextBuilder) super.sslSession(sslSession);
    }

    @Override
    public ServiceRequestContextBuilder requestStartTime(long requestStartTimeNanos,
                                                         long requestStartTimeMicros) {
        return (ServiceRequestContextBuilder) super.requestStartTime(requestStartTimeNanos,
                                                                     requestStartTimeMicros);
    }
}
