/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.logging;

import static com.linecorp.armeria.common.logging.MessageLogConsumerInvoker.invokeOnRequest;
import static com.linecorp.armeria.common.logging.MessageLogConsumerInvoker.invokeOnResponse;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.MessageLogConsumer;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.ResponseLog;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Decorates a {@link Service} to collects {@link RequestLog} and {@link ResponseLog} for every {@link Request}.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public class LogCollectingService<I extends Request, O extends Response> extends DecoratingService<I, O, I, O> {

    private final MessageLogConsumer consumer;

    /**
     * Creates a new instance.
     *
     * @param delegate the {@link Service} being decorated
     * @param consumer the consumer of the collected {@link RequestLog}s and {@link ResponseLog}s
     */
    public LogCollectingService(Service<? super I, ? extends O> delegate, MessageLogConsumer consumer) {
        super(delegate);
        this.consumer = requireNonNull(consumer, "consumer");
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        ctx.requestLogFuture()
           .thenAccept(log -> invokeOnRequest(consumer, ctx, log))
           .exceptionally(CompletionActions::log);
        ctx.responseLogFuture()
           .thenAccept(log -> invokeOnResponse(consumer, ctx, log))
           .exceptionally(CompletionActions::log);

        return delegate().serve(ctx, req);
    }
}
