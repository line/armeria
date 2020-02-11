/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.composition;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.Routed;
import com.linecorp.armeria.server.Router;
import com.linecorp.armeria.server.Routers;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceCallbackInvoker;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContextWrapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A skeletal {@link Service} implementation that enables composing multiple {@link Service}s into one.
 * Extend this class to build your own composite {@link Service}. e.g.
 * <pre>{@code
 * public class MyService extends AbstractCompositeService<HttpService, HttpRequest, HttpResponse> {
 *     public MyService() {
 *         super(CompositeServiceEntry.ofPrefix("/foo/", new FooService()),
 *               CompositeServiceEntry.ofPrefix("/bar/", new BarService()),
 *               CompositeServiceEntry.ofCatchAll(new OtherService()));
 *     }
 * }
 * }</pre>
 *
 * @param <T> the {@link Service} type
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 *
 * @see AbstractCompositeServiceBuilder
 * @see CompositeServiceEntry
 */
public abstract class AbstractCompositeService<T extends Service<I, O>, I extends Request, O extends Response>
        implements Service<I, O> {

    private final List<CompositeServiceEntry<T>> services;
    @Nullable
    private Server server;
    @Nullable
    private Router<T> router;

    /**
     * Creates a new instance with the specified {@link CompositeServiceEntry}s.
     */
    @SafeVarargs
    protected AbstractCompositeService(CompositeServiceEntry<T>... services) {
        this(ImmutableList.copyOf(requireNonNull(services, "services")));
    }

    /**
     * Creates a new instance with the specified {@link CompositeServiceEntry}s.
     */
    protected AbstractCompositeService(Iterable<CompositeServiceEntry<T>> services) {
        requireNonNull(services, "services");

        this.services = ImmutableList.copyOf(services);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        checkState(server == null, "cannot be added to more than one server");
        server = cfg.server();

        final Route route = cfg.route();
        if (route.pathType() != RoutePathType.PREFIX) {
            throw new IllegalStateException("The path type of the route must be " + RoutePathType.PREFIX +
                                            ", path type: " + route.pathType());
        }

        router = Routers.ofCompositeService(services);

        final MeterRegistry registry = server.meterRegistry();
        final MeterIdPrefix meterIdPrefix;
        if (Flags.useLegacyMeterNames()) {
            meterIdPrefix = new MeterIdPrefix("armeria.server.router.compositeServiceCache",
                                              "hostnamePattern", cfg.virtualHost().hostnamePattern(),
                                              "route", route.meterTag());
        } else {
            meterIdPrefix = new MeterIdPrefix("armeria.server.router.composite.service.cache",
                                              "hostname.pattern", cfg.virtualHost().hostnamePattern(),
                                              "route", route.meterTag());
        }

        router.registerMetrics(registry, meterIdPrefix);
        for (CompositeServiceEntry<T> e : services()) {
            ServiceCallbackInvoker.invokeServiceAdded(cfg, e.service());
        }
    }

    /**
     * Returns the list of {@link CompositeServiceEntry}s added to this composite {@link Service}.
     */
    protected List<CompositeServiceEntry<T>> services() {
        return services;
    }

    /**
     * Returns the {@code index}-th {@link Service} in this composite {@link Service}. The index of the
     * {@link Service} added first is {@code 0}, and so on.
     */
    protected T serviceAt(int index) {
        return services().get(index).service();
    }

    /**
     * Finds the {@link Service} whose {@link Route} matches the {@code path}.
     *
     * @param routingCtx a context to find the {@link Service}.
     *
     * @return the {@link Service} wrapped by {@link Routed} if there's a match.
     *         {@link Routed#empty()} if there's no match.
     */
    protected Routed<T> findService(RoutingContext routingCtx) {
        assert router != null;
        return router.find(routingCtx);
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        final RoutingContext routingCtx = ctx.routingContext();
        final Routed<T> result = findService(routingCtx.overridePath(ctx.mappedPath()));
        if (!result.isPresent()) {
            throw HttpStatusException.of(HttpStatus.NOT_FOUND);
        }

        if (result.route().pathType() == RoutePathType.PREFIX) {
            assert ctx.route().pathType() == RoutePathType.PREFIX;
            final Route newRoute = Route.builder()
                                        .pathPrefix(ctx.route().paths().get(0) +
                                                    result.route().paths().get(0).substring(1)).build();

            final ServiceRequestContext newCtx = new CompositeServiceRequestContext(
                    ctx, newRoute, result.routingResult().path());
            try (SafeCloseable ignored = newCtx.replace()) {
                return result.value().serve(newCtx, req);
            }
        } else {
            return result.value().serve(ctx, req);
        }
    }

    private static final class CompositeServiceRequestContext extends ServiceRequestContextWrapper {

        private final Route route;
        private final String mappedPath;
        @Nullable
        private String decodedMappedPath;

        CompositeServiceRequestContext(ServiceRequestContext delegate, Route route, String mappedPath) {
            super(delegate);
            this.route = route;
            this.mappedPath = mappedPath;
        }

        @Override
        public ServiceRequestContext newDerivedContext(RequestId id,
                                                       @Nullable HttpRequest req,
                                                       @Nullable RpcRequest rpcReq) {
            return new CompositeServiceRequestContext(super.newDerivedContext(id, req, rpcReq),
                                                      route, mappedPath);
        }

        @Override
        public Route route() {
            return route;
        }

        @Override
        public String mappedPath() {
            return mappedPath;
        }

        @Override
        public String decodedMappedPath() {
            final String decodedMappedPath = this.decodedMappedPath;
            if (decodedMappedPath != null) {
                return decodedMappedPath;
            }

            return this.decodedMappedPath = ArmeriaHttpUtil.decodePath(mappedPath);
        }
    }
}
