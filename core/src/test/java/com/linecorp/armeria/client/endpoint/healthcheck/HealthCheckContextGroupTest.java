/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.client.endpoint.healthcheck.AbstractHealthCheckedEndpointGroupBuilder.DEFAULT_HEALTH_CHECK_RETRY_BACKOFF;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroupTest.EndpointComparator;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroupTest.MockEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;

class HealthCheckContextGroupTest {

    @Test
    void shouldTransitionToNewEndpointsAfterHealthCheck() {
        final ArrayList<HealthCheckerContext> contexts = new ArrayList<>();
        final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkFactory = ctx -> {
            contexts.add(ctx);
            return AsyncCloseableSupport.of();
        };

        final MockEndpointGroup firstGroup = new MockEndpointGroup();
        final EndpointGroup secondGroup = EndpointGroup.of(Endpoint.of("static1"), Endpoint.of("static2"));
        final EndpointGroup delegate = firstGroup.orElse(secondGroup);

        try (HealthCheckedEndpointGroup endpointGroup =
                     new HealthCheckedEndpointGroup(delegate, SessionProtocol.HTTP, 80,
                                                    DEFAULT_HEALTH_CHECK_RETRY_BACKOFF,
                                                    ClientOptions.of(), checkFactory,
                                                    HealthCheckStrategy.all())) {
            assertThat(contexts).hasSize(2);
            // Health status is not updated yet.
            assertThat(endpointGroup.endpoints()).isEmpty();

            contexts.forEach(ctx -> ctx.updateHealth(1.0));
            assertThat(endpointGroup.endpoints()).containsAll(secondGroup.endpoints());

            firstGroup.set(Endpoint.of("dynamic1"), Endpoint.of("dynamic2"));
            assertThat(contexts).hasSize(4);
            // Health check for firstGroup is not finished yet.
            // The endpoints should not be changed to the firstGroup or be empty.
            assertThat(endpointGroup.endpoints()).containsAll(secondGroup.endpoints());

            // If health check is finished for the new endpoints,
            // the old EndpointGroup should be removed from the healthy endpoints.
            contexts.forEach(ctx -> ctx.updateHealth(1.0));
            assertThat(endpointGroup.endpoints()).containsAll(firstGroup.endpoints());

            for (int i = 0; i < contexts.size(); i++) {
                final DefaultHealthCheckerContext context = (DefaultHealthCheckerContext) contexts.get(i);
                if (i < 2) {
                    // Make sure the contexts for old endpoints was destroyed.
                    assertThat(context.refCnt()).isZero();
                } else {
                    assertThat(context.refCnt()).isOne();
                }
            }

            // Only weight is changed
            final Endpoint dynamic1WithWeight = Endpoint.of("dynamic1").withWeight(20);
            final Endpoint dynamic2 = Endpoint.of("dynamic2");
            firstGroup.set(dynamic1WithWeight, dynamic2);
            // Should reuse the exising contexts
            assertThat(contexts).hasSize(4);

            assertThat(endpointGroup.endpoints()).usingElementComparator(new EndpointComparator())
                    .containsExactly(dynamic1WithWeight, dynamic2);

            for (int i = 0; i < contexts.size(); i++) {
                final DefaultHealthCheckerContext context = (DefaultHealthCheckerContext) contexts.get(i);
                if (i < 2) {
                    assertThat(context.refCnt()).isZero();
                } else {
                    assertThat(context.refCnt()).isOne();
                }
            }
        }
    }

    @Test
    void shouldWaitUntilAllEndpointsAreChecked() {
        final ArrayList<HealthCheckerContext> contexts = new ArrayList<>();
        final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkFactory = ctx -> {
            contexts.add(ctx);
            return AsyncCloseableSupport.of();
        };

        final Endpoint static1 = Endpoint.of("static1");
        final Endpoint static2 = Endpoint.of("static2");
        final EndpointGroup delegate = EndpointGroup.of(static1, static2);

        try (HealthCheckedEndpointGroup endpointGroup =
                     new HealthCheckedEndpointGroup(delegate, SessionProtocol.HTTP, 80,
                                                    DEFAULT_HEALTH_CHECK_RETRY_BACKOFF,
                                                    ClientOptions.of(), checkFactory,
                                                    HealthCheckStrategy.all())) {
            assertThat(contexts).hasSize(2);
            // Health status is not updated yet.
            assertThat(endpointGroup.endpoints()).isEmpty();

            contexts.get(0).updateHealth(1.0);
            // The initial future should be completed after all endpoints are checked.
            assertThat(endpointGroup.whenReady()).isNotDone();

            contexts.get(1).updateHealth(0);
            assertThat(endpointGroup.whenReady()).isDone();
            assertThat(endpointGroup.endpoints()).containsExactly(contexts.get(0).endpoint());

            contexts.get(1).updateHealth(1.0);
            assertThat(endpointGroup.endpoints()).containsExactly(static1, static2);
        }
    }
}
