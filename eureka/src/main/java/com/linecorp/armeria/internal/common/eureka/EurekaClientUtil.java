/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.eureka;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.eureka.EurekaEndpointGroupBuilder;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.server.eureka.EurekaUpdatingListenerBuilder;

/**
 * A utility class for {@link EurekaEndpointGroupBuilder} and {@link EurekaUpdatingListenerBuilder}.
 */
public final class EurekaClientUtil {

    private static final ClientOptions retryingClientOptions =
            ClientOptions.of(ClientOptions.DECORATION.newValue(ClientDecoration.of(
                    RetryingClient.builder(RetryRule.failsafe()).maxTotalAttempts(3).newDecorator())));

    /**
     * Returns the {@link ClientOptions} that has {@link RetryingClient} decorator.
     * The decorator is created with {@link RetryRule#failsafe()} and {@code maxTotalAttempts} that is set to 3.
     */
    public static ClientOptions retryingClientOptions() {
        return retryingClientOptions;
    }

    private EurekaClientUtil() {}
}
