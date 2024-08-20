/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.client.endpoint.healthcheck;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

public final class StaticHttpHealthChecker implements HttpHealthChecker {

    public static HttpHealthChecker of(HealthCheckerContext ctx, double healthy) {
        return new StaticHttpHealthChecker(ctx, healthy);
    }

    private static final ClientRequestContext NOOP_CTX =
            ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    private StaticHttpHealthChecker(HealthCheckerContext ctx, double healthy) {
        ctx.updateHealth(healthy, NOOP_CTX, null, null);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
    }
}
