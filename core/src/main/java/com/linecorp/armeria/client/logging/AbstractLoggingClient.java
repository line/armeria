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

package com.linecorp.armeria.client.logging;

import static com.linecorp.armeria.internal.common.logging.LoggingUtils.log;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Sampler;

/**
 * Decorates a {@link Client} to log {@link Request}s and {@link Response}s.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
abstract class AbstractLoggingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private final LogWriter logWriter;
    private final Sampler<? super RequestLog> sampler;

    AbstractLoggingClient(Client<I, O> delegate, LogWriter logWriter,
                          Sampler<? super ClientRequestContext> successSampler,
                          Sampler<? super ClientRequestContext> failureSampler) {
        super(requireNonNull(delegate, "delegate"));
        this.logWriter = requireNonNull(logWriter, "logWriter");
        requireNonNull(successSampler, "successSampler");
        requireNonNull(failureSampler, "failureSampler");
        sampler = requestLog -> {
            final ClientRequestContext ctx = (ClientRequestContext) requestLog.context();
            if (ctx.options().successFunction().isSuccess(ctx, requestLog)) {
                return successSampler.isSampled(ctx);
            }
            return failureSampler.isSampled(ctx);
        };
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        ctx.log().whenComplete().thenAccept(log -> {
            if (sampler.isSampled(log)) {
                log(ctx, log, logWriter);
            }
        });
        return unwrap().execute(ctx, req);
    }
}
