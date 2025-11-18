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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.endpoint.healthcheck.HttpHealthChecker;

final class StaticHttpHealthChecker implements HttpHealthChecker {

    public static HttpHealthChecker of(HealthCheckerContext ctx, double healthy) {
        return new StaticHttpHealthChecker(ctx, healthy);
    }

    private StaticHttpHealthChecker(HealthCheckerContext ctx, double healthy) {
        ctx.updateHealth(healthy, null, null, null);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return UnmodifiableFuture.completedFuture(null);
    }

    @Override
    public void close() {
    }
}
