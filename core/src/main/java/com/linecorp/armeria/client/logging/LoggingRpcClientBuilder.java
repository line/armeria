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

import java.util.function.Function;

import com.linecorp.armeria.client.RpcClient;

/**
 * Builds a new {@link LoggingRpcClient}.
 */
public final class LoggingRpcClientBuilder extends AbstractLoggingClientBuilder<LoggingRpcClientBuilder> {

    /**
     * Creates a new instance.
     */
    LoggingRpcClientBuilder() {}

    /**
     * Returns a newly-created {@link LoggingRpcClient} decorating {@code delegate} based on the properties of
     * this builder.
     */
    public LoggingRpcClient build(RpcClient delegate) {
        return new LoggingRpcClient(delegate,
                                    null,
                                    requestLogLevelMapper(),
                                    responseLogLevelMapper(),
                                    requestHeadersSanitizer(),
                                    requestContentSanitizer(),
                                    requestTrailersSanitizer(),
                                    responseHeadersSanitizer(),
                                    responseContentSanitizer(),
                                    responseTrailersSanitizer(),
                                    responseCauseSanitizer(),
                                    sampler());
    }

    /**
     * Returns a newly-created {@link LoggingRpcClient} decorator based on the properties of this builder.
     */
    public Function<? super RpcClient, LoggingRpcClient> newDecorator() {
        return this::build;
    }
}
