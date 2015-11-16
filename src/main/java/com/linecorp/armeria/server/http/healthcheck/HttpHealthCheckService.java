/*
 * Copyright 2015 LINE Corporation
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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceInvocationHandler;
import com.linecorp.armeria.server.http.HttpService;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Promise;

/**
 * An {@link HttpService} that responds with HTTP status {@code "200 OK"} if the server is healthy and can
 * accept requests and HTTP status {@code "503 Service Not Available"} if the server is unhealthy and cannot
 * accept requests. The default behavior is to respond healthy after the server is started and unhealthy
 * after it started to stop.
 *
 * <p>Subclasses can override {@link #newHealthyResponse(ServiceInvocationContext)} or
 * {@link #newUnhealthyResponse(ServiceInvocationContext)} if they need to customize the response.</p>
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
 * <p>You can also specify additional {@link HealthChecker}s when constructing an
 * {@link HttpHealthCheckHandler}. It will respond with a unhealthy response if the
 * {@link HealthChecker#isHealthy()} method returns {@code false} for any of them.
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
public class HttpHealthCheckService extends HttpService {

    private static final byte[] RES_OK = "ok".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RES_NOT_OK = "not ok".getBytes(StandardCharsets.US_ASCII);

    private final List<HealthChecker> healthCheckers;
    private final ServerListener serverHealthUpdater;
    private final ServiceInvocationHandler handler;

    final SettableHealthChecker serverHealth;

    private Server server;

    public HttpHealthCheckService(HealthChecker... healthCheckers) {
        this.healthCheckers = Collections.unmodifiableList(Arrays.asList(healthCheckers));
        serverHealth = new SettableHealthChecker();
        serverHealthUpdater = new ServerHealthUpdater();
        handler = new HttpHealthCheckHandler();
    }

    /**
     * Creates a new response which is sent when the {@link Server} is healthy.
     */
    protected FullHttpResponse newHealthyResponse(
            @SuppressWarnings("UnusedParameters") ServiceInvocationContext ctx) {

        return new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(RES_OK));
    }

    /**
     * Creates a new response which is sent when the {@link Server} is unhealthy.
     */
    protected FullHttpResponse newUnhealthyResponse(
            @SuppressWarnings("UnusedParameters") ServiceInvocationContext ctx) {

        return new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                Unpooled.wrappedBuffer(RES_NOT_OK));
    }

    @Override
    public void serviceAdded(Server server) throws Exception {
        super.serviceAdded(server);

        if (this.server != null) {
            throw new IllegalStateException("cannot be added to more than one server");
        }

        this.server = server;
        server.addListener(serverHealthUpdater);
    }

    @Override
    public ServiceInvocationHandler handler() {
        return handler;
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

    final class HttpHealthCheckHandler implements ServiceInvocationHandler {
        @Override
        public void invoke(ServiceInvocationContext ctx,
                           Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {

            FullHttpResponse response;
            if (isHealthy()) {
                response = newHealthyResponse(ctx);
            } else {
                response = newUnhealthyResponse(ctx);
            }
            promise.setSuccess(response);
        }

        private boolean isHealthy() {
            for (HealthChecker healthChecker : healthCheckers) {
                if (!healthChecker.isHealthy()) {
                    return false;
                }
            }
            return serverHealth.isHealthy();
        }
    }
}
