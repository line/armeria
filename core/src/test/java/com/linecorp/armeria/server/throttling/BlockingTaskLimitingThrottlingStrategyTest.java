/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.throttling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.util.LimitedBlockingTaskExecutor;
import com.linecorp.armeria.server.ServiceRequestContext;

class BlockingTaskLimitingThrottlingStrategyTest {
    final LimitedBlockingTaskExecutor executor = mock(LimitedBlockingTaskExecutor.class);

    @Test
    void blockingTaskLimiting() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);

        final ThrottlingStrategy<Request> strategy =
                new BlockingTaskLimitingThrottlingStrategy<>(executor, null);

        when(executor.hitLimit()).thenReturn(true);
        assertThat(strategy.accept(ctx, req).toCompletableFuture().join()).isFalse();

        when(executor.hitLimit()).thenReturn(false);
        assertThat(strategy.accept(ctx, req).toCompletableFuture().join()).isTrue();
    }
}
