/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.throttling;

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

public class ThrottlingStrategyTest {
    @Test
    public void nameAndFailureStatus() {
        assertThat(ThrottlingStrategy.always().name()).isEqualTo("throttling-strategy-always");

        assertThat(ThrottlingStrategy.never().name()).isEqualTo("throttling-strategy-never");

        final ThrottlingStrategy<HttpRequest> strategy1 =
                ThrottlingStrategy.of((ctx, req) -> completedFuture(false), "test-strategy");
        assertThat(strategy1.name()).isEqualTo("test-strategy");

        final ThrottlingStrategy<RpcRequest> strategy2 = new TestThrottlingStrategy();
        assertThat(strategy2.name()).isEqualTo("throttling-strategy-TestThrottlingStrategy");
    }

    private static class TestThrottlingStrategy extends ThrottlingStrategy<RpcRequest> {
        @Override
        public CompletionStage<Boolean> accept(ServiceRequestContext ctx, RpcRequest request) {
            return completedFuture(true);
        }
    }
}
