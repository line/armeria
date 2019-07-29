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

package com.linecorp.armeria.testing.junit.server.mockwebserver;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.AggregatedHttpResponse;

/**
 * A builder of {@link MockResponse}. Obtain a new builder by calling
 * {@link MockResponse#builder(AggregatedHttpResponse)}.
 */
public class MockResponseBuilder {

    private final AggregatedHttpResponse response;

    private Duration delay = Duration.ZERO;

    MockResponseBuilder(AggregatedHttpResponse response) {
        this.response = response;
    }

    /**
     * Sets a time to wait before returning the response. Can be useful to validate client timeout behavior.
     */
    public MockResponseBuilder delay(Duration headersDelay) {
        this.delay = headersDelay;
        return this;
    }

    /**
     * Sets a time to wait before returning the response. Can be useful to validate client timeout behavior.
     */
    public MockResponseBuilder delay(int amount, TimeUnit timeUnit) {
        this.delay = Duration.ofMillis(timeUnit.toMillis(amount));
        return this;
    }

    /**
     * Returns a new {@link MockResponse} with the configured parameters.
     */
    public MockResponse build() {
        return new MockResponse(response, delay);
    }
}
