/*
 * Copyright 2017 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.LoggingDecoratorBuilder;
import com.linecorp.armeria.internal.logging.Sampler;

/**
 * Builds a new {@link LoggingClient}.
 */
public class LoggingClientBuilder extends LoggingDecoratorBuilder<LoggingClientBuilder> {

    /**
     * Returns a newly-created {@link LoggingClient} decorating {@code delegate} based on the properties of
     * this builder.
     */
    public <I extends Request, O extends Response> LoggingClient<I, O> build(Client<I, O> delegate) {
        return new LoggingClient<>(delegate,
                                   requestLogLevel(),
                                   successfulResponseLogLevel(),
                                   failedResponseLogLevel(),
                                   requestHeadersSanitizer(),
                                   requestContentSanitizer(),
                                   responseHeadersSanitizer(),
                                   responseContentSanitizer(),
                                   Sampler.create(samplingRate()));
    }

    /**
     * Returns a newly-created {@link LoggingClient} decorator based on the properties of this builder.
     */
    public <I extends Request, O extends Response> Function<Client<I, O>, LoggingClient<I, O>>
    newDecorator() {
        return this::build;
    }
}
