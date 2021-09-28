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

package com.linecorp.armeria.client.endpoint;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Selects an {@link Endpoint} from an {@link EndpointGroup}.
 */
public interface EndpointSelector {
    /**
     * Selects an {@link Endpoint} from the {@link EndpointGroup} associated with the specified
     * {@link ClientRequestContext}.
     *
     * @return the {@link Endpoint} selected by this {@link EndpointSelector}'s selection strategy,
     *         or {@code null} if no {@link Endpoint} was selected, which can happen
     *         if the {@link EndpointGroup} is empty.
     */
    @Nullable
    Endpoint selectNow(ClientRequestContext ctx);

    /**
     * Selects an {@link Endpoint} asynchronously from the {@link EndpointGroup} associated with the specified
     * {@link ClientRequestContext}, waiting up to the specified {@code timeoutMillis}.
     *
     * @param ctx the {@link ClientRequestContext} of the {@link Request} being handled.
     * @param executor the {@link ScheduledExecutorService} used for notifying the {@link CompletableFuture}
     *                 being returned and scheduling timeout tasks.
     * @param timeoutMillis the amount of milliseconds to wait until a successful {@link Endpoint} selection.
     *
     * @return the {@link CompletableFuture} that will be completed with the {@link Endpoint} selected by
     *         this {@link EndpointSelector}'s selection strategy, or completed with {@code null} if no
     *         {@link Endpoint} was selected within the specified {@code timeoutMillis}, which can happen
     *         if the {@link EndpointGroup} is empty.
     */
    CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                       ScheduledExecutorService executor,
                                       long timeoutMillis);
}
