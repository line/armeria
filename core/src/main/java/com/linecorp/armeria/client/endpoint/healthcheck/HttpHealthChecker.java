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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.net.StandardProtocolFamily;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;

final class HttpHealthChecker implements AsyncCloseable {

    private final HealthCheckerContext ctx;
    private final HttpClient httpClient;
    private final String path;
    @Nullable
    private CompletableFuture<?> lastCheckFuture;

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
            builder.setHttpHeader(HttpHeaderNames.AUTHORITY, endpoint.authority());
        }

        this.ctx = ctx;
        httpClient = builder.factory(ctx.clientFactory())
                            .options(ctx.clientConfigurator().apply(new ClientOptionsBuilder()).build())
                            .build();
        this.path = path;
    }

    void start() {
        check();
    }

    private void check() {
        final CompletableFuture<AggregatedHttpResponse> f;
        synchronized (this) {
            f = httpClient.head(path).aggregate();
            lastCheckFuture = f;
        }

        f.handle((res, cause) -> {
            if (res != null && res.status().equals(HttpStatus.OK)) {
                ctx.updateHealth(1);
            } else {
                ctx.updateHealth(0);
            }

            ctx.executor().schedule(this::check, ctx.nextDelayMillis(), TimeUnit.MILLISECONDS);
            return null;
        });
    }

    @Override
    public synchronized CompletableFuture<?> closeAsync() {
        if (lastCheckFuture != null) {
            return lastCheckFuture.handle((unused1, unused2) -> null);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }
}
