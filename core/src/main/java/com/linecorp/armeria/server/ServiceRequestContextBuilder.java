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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import com.linecorp.armeria.common.AbstractRequestContextBuilder;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.TimeoutScheduler;
import com.linecorp.armeria.internal.common.TimeoutScheduler.TimeoutTask;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ImmediateEventExecutor;

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

    private static final TimeoutTask noopTimeoutTask = new TimeoutTask() {
        @Override
        public boolean canSchedule() {
            return true;
        }

        @Override
        public void run() { /* no-op */ }
    };

    /**
     * A timeout scheduler that has been timed-out.
     */
    private static final TimeoutScheduler noopRequestCancellationScheduler = new TimeoutScheduler(0);

    static {
        noopRequestCancellationScheduler.init(ImmediateEventExecutor.INSTANCE, noopTimeoutTask, 0);
        noopRequestCancellationScheduler.finishNow();
    }

    private final List<Consumer<? super ServerBuilder>> serverConfigurators = new ArrayList<>(4);

    private HttpService service = fakeService;
    @Nullable
    private String defaultServiceName;
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
        this.defaultServiceName = requireNonNull(defaultServiceName, "defaultServiceName");
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
            proxiedAddresses = ProxiedAddresses.of((InetSocketAddress) remoteAddress());
        }

        // Build a fake server which never starts up.
        final ServerBuilder serverBuilder = Server.builder()
                                                  .meterRegistry(meterRegistry())
                                                  .workerGroup(eventLoop(), false);

        final ServiceBindingBuilder serviceBindingBuilder;
        if (route != null) {
            serviceBindingBuilder = serverBuilder.route().addRoute(route);
        } else {
            serviceBindingBuilder = serverBuilder.route().path(path());
        }

        if (defaultServiceName != null) {
            serviceBindingBuilder.defaultServiceName(defaultServiceName);
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
                ((InetSocketAddress) localAddress()).getHostString(),
                path(),
                query(),
                req.headers(),
                false);

        final RoutingResult routingResult =
                this.routingResult != null ? this.routingResult
                                           : RoutingResult.builder().path(path()).query(query()).build();
        final InetAddress clientAddress = server.config().clientAddressMapper().apply(proxiedAddresses)
                                                .getAddress();

        final TimeoutScheduler requestCancellationScheduler;
        if (timedOut()) {
            requestCancellationScheduler = noopRequestCancellationScheduler;
        } else {
            requestCancellationScheduler = new TimeoutScheduler(0);
            final CountDownLatch latch = new CountDownLatch(1);
            eventLoop().execute(() -> {
                requestCancellationScheduler.init(eventLoop(), noopTimeoutTask, 0);
                latch.countDown();
            });

            try {
                latch.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        // Build the context with the properties set by a user and the fake objects.
        return new DefaultServiceRequestContext(
                serviceCfg, fakeChannel(), meterRegistry(), sessionProtocol(), id(), routingCtx,
                routingResult, req, sslSession(), proxiedAddresses, clientAddress,
                requestCancellationScheduler,
                isRequestStartTimeSet() ? requestStartTimeNanos() : System.nanoTime(),
                isRequestStartTimeSet() ? requestStartTimeMicros() : SystemInfo.currentTimeMicros(),
                HttpHeaders.of(), HttpHeaders.of());
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
    public ServiceRequestContextBuilder remoteAddress(SocketAddress remoteAddress) {
        requireNonNull(remoteAddress, "remoteAddress");
        checkArgument(remoteAddress instanceof InetSocketAddress,
                      "remoteAddress: %s (expected: an InetSocketAddress)", remoteAddress);
        return (ServiceRequestContextBuilder) super.remoteAddress(remoteAddress);
    }

    @Override
    public ServiceRequestContextBuilder localAddress(SocketAddress localAddress) {
        requireNonNull(localAddress, "remoteAddress");
        checkArgument(localAddress instanceof InetSocketAddress,
                      "localAddress: %s (expected: an InetSocketAddress)", localAddress);
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
