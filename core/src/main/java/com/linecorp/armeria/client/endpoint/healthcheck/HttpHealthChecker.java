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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.net.StandardProtocolFamily;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.math.LongMath;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.util.AsyncCloseable;

import io.netty.util.AsciiString;

final class HttpHealthChecker implements AsyncCloseable {

    private static final AsciiString ARMERIA_LPHC = HttpHeaderNames.of("armeria-lphc");

    private final HealthCheckerContext ctx;
    private final HttpClient httpClient;
    private final String path;
    private boolean wasHealthy;
    private long maxLongPollingSeconds;
    @Nullable
    private HttpResponse lastResponse;

    HttpHealthChecker(HealthCheckerContext ctx, String path) {

        final Endpoint endpoint = ctx.endpoint();
        final SessionProtocol protocol = ctx.protocol();
        final String scheme = protocol.uriText();
        final String ipAddr = endpoint.ipAddr();
        final HttpClientBuilder builder;
        if (ipAddr == null) {
            builder = new HttpClientBuilder(scheme + "://" + endpoint.authority());
        } else {
            final int port = ctx.port() > 0 ? ctx.port() : endpoint.port(protocol.defaultPort());
            if (endpoint.ipFamily() == StandardProtocolFamily.INET) {
                builder = new HttpClientBuilder(scheme + "://" + ipAddr + ':' + port);
            } else {
                builder = new HttpClientBuilder(scheme + "://[" + ipAddr + "]:" + port);
            }
        }

        this.ctx = ctx;
        httpClient = builder.factory(ctx.clientFactory())
                            .options(ctx.clientConfigurator().apply(new ClientOptionsBuilder()).build())
                            .decorator(ResponseTimeoutUpdater::new)
                            .build();
        this.path = path;
    }

    void start() {
        check();
    }

    private synchronized void check() {
        final RequestHeaders headers;
        final RequestHeadersBuilder builder =
                RequestHeaders.builder(HttpMethod.HEAD, path)
                              .add(HttpHeaderNames.AUTHORITY, ctx.endpoint().authority());
        if (maxLongPollingSeconds > 0) {
            headers = builder.add(HttpHeaderNames.IF_NONE_MATCH, wasHealthy ? "\"healthy\"" : "\"unhealthy\"")
                             .add(HttpHeaderNames.PREFER, "wait=" + maxLongPollingSeconds)
                             .build();
        } else {
            headers = builder.build();
        }

        lastResponse = httpClient.execute(headers);
        lastResponse.aggregate().handle((res, cause) -> {
            boolean isHealthy = false;
            if (res != null) {
                maxLongPollingSeconds = Math.max(0, res.headers().getLong(ARMERIA_LPHC, 0));
                if (res.status().equals(HttpStatus.OK)) {
                    isHealthy = true;
                }
            } else {
                maxLongPollingSeconds = 0;
            }
            ctx.updateHealth(isHealthy ? 1 : 0);
            wasHealthy = isHealthy;

            try {
                final ScheduledExecutorService executor = ctx.executor();
                final long nextDelayMillis = ctx.nextDelayMillis();
                if (res != null && maxLongPollingSeconds > 0) {
                    executor.execute(this::check);
                } else {
                    executor.schedule(this::check, nextDelayMillis, TimeUnit.MILLISECONDS);
                }
            } catch (RejectedExecutionException ignored) {
                // Can happen if the Endpoint being checked has been disappeared from
                // the delegate EndpointGroup. See HealthCheckedEndpointGroupTest.disappearedEndpoint().
            }
            return null;
        });
    }

    @Override
    public synchronized CompletableFuture<?> closeAsync() {
        if (lastResponse != null) {
            lastResponse.abort();
            return lastResponse.completionFuture().handle((unused1, unused2) -> null);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private final class ResponseTimeoutUpdater extends SimpleDecoratingHttpClient {
        ResponseTimeoutUpdater(Client<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            if (maxLongPollingSeconds > 0) {
                final long responseTimeoutMillis = ctx.responseTimeoutMillis();
                if (responseTimeoutMillis > 0) {
                    final long newResponseTimeoutMillis = LongMath.saturatedAdd(
                            responseTimeoutMillis,
                            TimeUnit.SECONDS.toMillis(maxLongPollingSeconds));
                    ctx.setResponseTimeoutMillis(newResponseTimeoutMillis);
                }
                ctx.log().addListener(log -> {
                    if (log.responseHeaders().status().code() == 0) {
                        System.err.println("?");
                    }
                }, RequestLogAvailability.COMPLETE);
            }
            return delegate().execute(ctx, req);
        }
    }
}
