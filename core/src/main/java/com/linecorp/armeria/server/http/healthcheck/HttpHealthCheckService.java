/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.healthcheck;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.server.http.HttpService;

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
 * Server server = new ServerBuilder()
 *         .defaultVirtualHost(new VirtualHostBuilder()
 *                 .serviceAt("/rpc", new ThriftService(myHandler))
 *                 .serviceAt("/health", new HttpHealthCheckService())
 *                 .build())
 *         .build();
 * }</pre>
 *
 * <p>You can also specify additional {@link HealthChecker}s at construction time. It will respond with a
 * unhealthy response if the {@link HealthChecker#isHealthy()} method returns {@code false} for any of them.
 * This can be useful when you want to stop receiving requests from a load balancer without stopping a
 * {@link Server}. e.g. the backend got unhealthy.</p>
 *
 * <pre>{@code
 * SettableHealthChecker healthChecker = new SettableHealthChecker();
 * Server server = new ServerBuilder()
 *         .defaultVirtualHost(new VirtualHostBuilder()
 *                 .serviceAt("/rpc", new ThriftService(myHandler))
 *                 .serviceAt("/health", new HttpHealthCheckService(healthChecker))
 *                 .build())
 *         .build();
 * }</pre>
 */
public class HttpHealthCheckService extends AbstractHttpService {

    private static final HttpData RES_OK = HttpData.ofAscii("ok");
    private static final HttpData RES_NOT_OK = HttpData.ofAscii("not ok");

    private final List<HealthChecker> healthCheckers;
    private final ServerListener serverHealthUpdater;

    final SettableHealthChecker serverHealth;

    private Server server;

    /**
     * Creates a new instance.
     *
     * @param healthCheckers the additional {@link HealthChecker}s
     */
    public HttpHealthCheckService(HealthChecker... healthCheckers) {
        this.healthCheckers = Collections.unmodifiableList(Arrays.asList(healthCheckers));
        serverHealth = new SettableHealthChecker();
        serverHealthUpdater = new ServerHealthUpdater();
    }

    /**
     * Creates a new response which is sent when the {@link Server} is healthy.
     */
    protected AggregatedHttpMessage newHealthyResponse(
            @SuppressWarnings("UnusedParameters") ServiceRequestContext ctx) {

        return AggregatedHttpMessage.of(HttpStatus.OK, RES_OK);
    }

    /**
     * Creates a new response which is sent when the {@link Server} is unhealthy.
     */
    protected AggregatedHttpMessage newUnhealthyResponse(
            @SuppressWarnings("UnusedParameters") ServiceRequestContext ctx) {

        return AggregatedHttpMessage.of(HttpStatus.SERVICE_UNAVAILABLE, RES_NOT_OK);
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
    protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
        final AggregatedHttpMessage response;
        if (isHealthy()) {
            response = newHealthyResponse(ctx);
        } else {
            response = newUnhealthyResponse(ctx);
        }

        res.respond(response);
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
        public void serverStarted(Server server) throws Exception {
            serverHealth.setHealthy(true);
        }

        @Override
        public void serverStopping(Server server) throws Exception {
            serverHealth.setHealthy(false);
        }
    }
}
