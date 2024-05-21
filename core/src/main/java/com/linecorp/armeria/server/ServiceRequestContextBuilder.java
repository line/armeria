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

import static com.linecorp.armeria.internal.common.CancellationScheduler.noopCancellationTask;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.AbstractRequestContextBuilder;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
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
        public void serverStarting(Server server) {
            throw new UnsupportedOperationException();
        }
    };

    private final List<Consumer<? super ServerBuilder>> serverConfigurators = new ArrayList<>(4);

    private HttpService service = fakeService;
    @Nullable
    private ServiceNaming defaultServiceNaming;
    @Nullable
    private String defaultLogName;
    @Nullable
    private Route route;
    @Nullable
    private RoutingResult routingResult;
    @Nullable
    private ProxiedAddresses proxiedAddresses;

    ServiceRequestContextBuilder(HttpRequest request) {
        super(true, request);
    }

    /**
     * Sets the {@link Service} that handles the request. If not set, a dummy {@link Service}, which always
     * returns a {@code "405 Method Not Allowed"} response, is used.
     */
    public ServiceRequestContextBuilder service(HttpService service) {
        this.service = requireNonNull(service, "service");
        return this;
    }

    /**
     * Sets the default value of the {@link RequestLog#serviceName()} property which is used when
     * no service name was set via {@link RequestLogBuilder#name(String, String)}.
     *
     * @param defaultServiceName the default log name.
     */
    public ServiceRequestContextBuilder defaultServiceName(String defaultServiceName) {
        requireNonNull(defaultServiceName, "defaultServiceName");
        return defaultServiceNaming(ServiceNaming.of(defaultServiceName));
    }

    /**
     * Sets the default naming rule for the {@link RequestLog#serviceName()}.
     * If set, the service name will be converted according to given naming rule.
     *
     * @param defaultServiceNaming the default service naming.
     */
    public ServiceRequestContextBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        this.defaultServiceNaming = requireNonNull(defaultServiceNaming, "defaultServiceNaming");
        return this;
    }

    /**
     * Sets the default value of the {@link RequestLog#name()} property which is used when no name was set via
     * {@link RequestLogBuilder#name(String, String)}.
     *
     * @param defaultLogName the default log name.
     */
    public ServiceRequestContextBuilder defaultLogName(String defaultLogName) {
        this.defaultLogName = requireNonNull(defaultLogName, "defaultLogName");
        return this;
    }

    /**
     * Sets the {@link Route} of the request. If not set, it is auto-generated from the request.
     */
    public ServiceRequestContextBuilder route(Route route) {
        this.route = requireNonNull(route, "route");
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
     * Adds the {@link Consumer} that configures the given {@link ServerBuilder}. The {@link Consumer}s added
     * by this method will be invoked when this builder builds a dummy {@link Server}. This may be useful
     * when you need to update the default settings of the dummy {@link Server},
     * such as {@link ServerBuilder#maxRequestLength(long)}.
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
        final ProxiedAddresses proxiedAddresses;
        if (this.proxiedAddresses != null) {
            proxiedAddresses = this.proxiedAddresses;
        } else {
            proxiedAddresses = ProxiedAddresses.of(remoteAddress());
        }

        // Build a fake server which never starts up.
        final ServerBuilder serverBuilder = Server.builder()
                                                  .meterRegistry(meterRegistry());

        final ServiceBindingBuilder serviceBindingBuilder;
        if (route != null) {
            serviceBindingBuilder = serverBuilder.route().addRoute(route);
        } else {
            serviceBindingBuilder = serverBuilder.route().path(requestTarget().path());
        }

        if (defaultServiceNaming != null) {
            serviceBindingBuilder.defaultServiceNaming(defaultServiceNaming);
        }
        if (defaultLogName != null) {
            serviceBindingBuilder.defaultLogName(defaultLogName);
        }
        serviceBindingBuilder.build(service);

        serverConfigurators.forEach(configurator -> configurator.accept(serverBuilder));

        final Server server = serverBuilder.build();
        server.addListener(rejectingListener);

        // Retrieve the ServiceConfig of the fake service.
        final ServiceConfig serviceCfg = findServiceConfig(server, service);

        // Build the fake objects related with path mapping.
        final HttpRequest req = request();
        assert req != null;

        final RoutingContext routingCtx = DefaultRoutingContext.of(
                server.config().defaultVirtualHost(),
                localAddress().getHostString(),
                requestTarget(),
                req.headers(),
                RoutingStatus.OK, sessionProtocol());

        final RoutingResult routingResult =
                this.routingResult != null ? this.routingResult
                                           : RoutingResult.builder()
                                                          .path(requestTarget().path())
                                                          .query(requestTarget().query())
                                                          .build();
        final Route route = Route.builder().path(requestTarget().path()).build();
        final Routed<ServiceConfig> routed = Routed.of(route, routingResult, serviceCfg);
        routingCtx.setResult(routed);
        final ExchangeType exchangeType = service.exchangeType(routingCtx);
        final InetAddress clientAddress = server.config().clientAddressMapper().apply(proxiedAddresses)
                                                .getAddress();

        EventLoop eventLoop = eventLoop();
        if (eventLoop == null) {
            eventLoop = CommonPools.workerGroup().next();
        }

        final CancellationScheduler requestCancellationScheduler;
        if (timedOut()) {
            requestCancellationScheduler = CancellationScheduler.finished(true);
        } else {
            requestCancellationScheduler = CancellationScheduler.ofServer(0);
            requestCancellationScheduler.initAndStart(eventLoop, noopCancellationTask);
        }

        // Build the context with the properties set by a user and the fake objects.
        final Channel ch = fakeChannel(eventLoop);
        return new DefaultServiceRequestContext(
                serviceCfg, ch, eventLoop, meterRegistry(), sessionProtocol(), id(), routingCtx,
                routingResult, exchangeType, req, sslSession(), proxiedAddresses,
                clientAddress, remoteAddress(), localAddress(),
                requestCancellationScheduler,
                isRequestStartTimeSet() ? requestStartTimeNanos() : System.nanoTime(),
                isRequestStartTimeSet() ? requestStartTimeMicros() : SystemInfo.currentTimeMicros(),
                HttpHeaders.of(), HttpHeaders.of(), serviceCfg.contextHook());
    }

    private static ServiceConfig findServiceConfig(Server server, HttpService service) {
        for (ServiceConfig cfg : server.config().defaultVirtualHost().serviceConfigs()) {
            if (cfg.service().as(service.getClass()) != null) {
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
    public ServiceRequestContextBuilder id(RequestId id) {
        return (ServiceRequestContextBuilder) super.id(id);
    }

    @Override
    public ServiceRequestContextBuilder remoteAddress(InetSocketAddress remoteAddress) {
        requireNonNull(remoteAddress, "remoteAddress");
        return (ServiceRequestContextBuilder) super.remoteAddress(remoteAddress);
    }

    @Override
    public ServiceRequestContextBuilder localAddress(InetSocketAddress localAddress) {
        requireNonNull(localAddress, "remoteAddress");
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

    @Override
    public ServiceRequestContextBuilder timedOut(boolean timedOut) {
        return (ServiceRequestContextBuilder) super.timedOut(timedOut);
    }
}
