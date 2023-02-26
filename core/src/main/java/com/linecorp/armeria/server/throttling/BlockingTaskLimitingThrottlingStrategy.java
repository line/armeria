/*
 * Copyright 2023 LINE Corporation
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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.LimitedBlockingTaskExecutor;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} which
 * provides a throttling strategy based on the count of the tasks of the {@link LimitedBlockingTaskExecutor}.
 * @see ThrottlingStrategy#blockingTaskLimiting(LimitedBlockingTaskExecutor, String)
 */
final class BlockingTaskLimitingThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {
    private final LimitedBlockingTaskExecutor executor;

    BlockingTaskLimitingThrottlingStrategy(LimitedBlockingTaskExecutor executor, @Nullable String name) {
        super(name);
        this.executor = executor;
    }

    @Override
    public CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request) {
        if (executor.hitLimit()) {
            return UnmodifiableFuture.completedFuture(false);
        }

        return UnmodifiableFuture.completedFuture(true);
    }
}
