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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;

class HealthCheckedEndpointGroupTest {
    @Test
    void disappearedEndpoint() {
        // Start with an endpoint group that has healthy 'foo'.
        final MockEndpointGroup delegate = new MockEndpointGroup();
        delegate.set(Endpoint.of("foo"));
        final AtomicReference<HealthCheckerContext> ctxCapture = new AtomicReference<>();

        try (HealthCheckedEndpointGroup group = new AbstractHealthCheckedEndpointGroupBuilder(
                delegate, ctx -> {
                    ctxCapture.set(ctx);
                    ctx.updateHealth(1);
                    return () -> CompletableFuture.completedFuture(null);
                }) {}.build()) {

            // Check the initial state.
            final HealthCheckerContext ctx = ctxCapture.get();
            assertThat(ctx).isNotNull();
            assertThat(ctx.endpoint()).isEqualTo(Endpoint.of("foo"));

            // 'foo' did not disappear yet, so the task must be accepted and run.
            final AtomicBoolean taskRun = new AtomicBoolean();
            ctx.executor().execute(() -> taskRun.set(true));
            await().untilAsserted(() -> assertThat(taskRun).isTrue());

            // Make 'foo' disappear.
            delegate.set();

            // 'foo' should not be healthy anymore.
            assertThat(group.endpoints()).isEmpty();

            // 'foo' should not be healthy even if `ctx.updateHealth()` was called.
            ctx.updateHealth(1);
            assertThat(group.endpoints()).isEmpty();
            assertThat(group.healthyEndpoints).isEmpty();

            // An attempt to schedule a new task for a disappeared endpoint must fail.
            assertThatThrownBy(() -> ctx.executor().execute(() -> {}))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("destroyed");
        }
    }

    private static final class MockEndpointGroup extends DynamicEndpointGroup {
        void set(Endpoint... endpoints) {
            setEndpoints(ImmutableList.copyOf(endpoints));
        }
    }
}
