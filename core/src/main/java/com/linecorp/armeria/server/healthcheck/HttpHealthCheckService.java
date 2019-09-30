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

package com.linecorp.armeria.server.healthcheck;

import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientService;

/**
 * An {@link HttpService} that responds with HTTP status {@code "200 OK"} if the server is healthy and can
 * accept requests and HTTP status {@code "503 Service Not Available"} if the server is unhealthy and cannot
 * accept requests. The default behavior is to respond healthy after the server is started and unhealthy
 * after it started to stop.
 *
 * <p>Subclasses can override {@link #newHealthyResponse(ServiceRequestContext)} or
 * {@link #newUnhealthyResponse(ServiceRequestContext)} if they need to customize the response.</p>
 *
 * <h2>Example:</h2>
 * <pre>{@code
 * Server server = Server.builder()
 *                       .service("/services", myService)
 *                       .service("/health", new HttpHealthCheckService())
 *                       .build();
 * }</pre>
 *
 * <p>You can also specify additional {@link HealthChecker}s at construction time. It will respond with a
 * unhealthy response if the {@link HealthChecker#isHealthy()} method returns {@code false} for any of them.
 * This can be useful when you want to stop receiving requests from a load balancer without stopping a
 * {@link Server}. e.g. the backend got unhealthy.</p>
 *
 * <pre>{@code
 * SettableHealthChecker healthChecker = new SettableHealthChecker();
 * Server server = Server.builder()
 *                       .service("/services", myService)
 *                       .service("/health", new HttpHealthCheckService(healthChecker))
 *                       .build();
 * }</pre>
 *
 * @deprecated Use {@link HealthCheckService}.
 */
@Deprecated
public class HttpHealthCheckService extends AbstractHttpService
        implements TransientService<HttpRequest, HttpResponse> {

    private static final HttpData RES_OK = HttpData.ofUtf8("ok");
    private static final HttpData RES_NOT_OK = HttpData.ofUtf8("not ok");

    private final List<HealthChecker> healthCheckers;
    private final ServerListener serverHealthUpdater;

    final SettableHealthChecker serverHealth;

    @Nullable
    private Server server;

    /**
     * Creates a new instance.
     *
     * @param healthCheckers the additional {@link HealthChecker}s
     */
    public HttpHealthCheckService(HealthChecker... healthCheckers) {
        this(ImmutableList.copyOf(requireNonNull(healthCheckers, "healthCheckers")));
    }

    /**
     * Creates a new instance.
     *
     * @param healthCheckers the additional {@link HealthChecker}s
     */
    public HttpHealthCheckService(Iterable<? extends HealthChecker> healthCheckers) {
        this.healthCheckers = ImmutableList.copyOf(requireNonNull(healthCheckers, "healthCheckers"));
        serverHealth = new SettableHealthChecker(false);
        serverHealthUpdater = new ServerHealthUpdater();
    }

    /**
     * Creates a new response which is sent when the {@link Server} is healthy.
     */
    protected AggregatedHttpResponse newHealthyResponse(
            @SuppressWarnings("UnusedParameters") ServiceRequestContext ctx) {
        return AggregatedHttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, RES_OK);
    }

    /**
     * Creates a new response which is sent when the {@link Server} is unhealthy.
     */
    protected AggregatedHttpResponse newUnhealthyResponse(
            @SuppressWarnings("UnusedParameters") ServiceRequestContext ctx) {
        return AggregatedHttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8,
                                         RES_NOT_OK);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);

        if (server != null) {
            if (server != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        server = cfg.server();
        server.addListener(serverHealthUpdater);
    }

    @Override
    protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(newResponse(ctx, req).headers()); // Send without the content.
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(newResponse(ctx, req));
    }

    private AggregatedHttpResponse newResponse(ServiceRequestContext ctx, HttpRequest req) {
        return isHealthy() ? newHealthyResponse(ctx)
                           : newUnhealthyResponse(ctx);
    }

    private boolean isHealthy() {
        for (HealthChecker healthChecker : healthCheckers) {
            if (!healthChecker.isHealthy()) {
                return false;
            }
        }
        return serverHealth.isHealthy();
    }

    final class ServerHealthUpdater extends ServerListenerAdapter {
        @Override
        public void serverStarted(Server server) {
            serverHealth.setHealthy(true);
        }

        @Override
        public void serverStopping(Server server) {
            serverHealth.setHealthy(false);
        }
    }
}
