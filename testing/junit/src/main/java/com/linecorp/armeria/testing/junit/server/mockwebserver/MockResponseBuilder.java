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

    private Duration headersDelay = Duration.ZERO;
    private Duration bodyDelay = Duration.ZERO;
    private Duration trailersDelay = Duration.ZERO;

    MockResponseBuilder(AggregatedHttpResponse response) {
        this.response = response;
    }

    /**
     * Sets a time to wait before returning response headers. Can be useful to validate client timeout behavior.
     */
    public MockResponseBuilder headersDelay(Duration headersDelay) {
        this.headersDelay = headersDelay;
        return this;
    }

    /**
     * Sets a time to wait before returning response headers. Can be useful to validate client timeout behavior.
     */
    public MockResponseBuilder headersDelay(int amount, TimeUnit timeUnit) {
        this.headersDelay = Duration.ofMillis(timeUnit.toMillis(amount));
        return this;
    }

    /**
     * Sets a time to wait before returning response content. Can be useful to validate client timeout behavior.
     * This value does not affect the time that headers are returned, to set that use
     * {@link #headersDelay(Duration)}.
     */
    public MockResponseBuilder contentDelay(Duration bodyDelay) {
        this.bodyDelay = bodyDelay;
        return this;
    }

    /**
     * Sets a time to wait before returning response content. Can be useful to validate client timeout behavior.
     * This value does not affect the time that headers are returned, to set that use
     * {@link #headersDelay(int, TimeUnit)}.
     */
    public MockResponseBuilder contentDelay(int amount, TimeUnit timeUnit) {
        this.bodyDelay = Duration.ofMillis(timeUnit.toMillis(amount));
        return this;
    }

    /**
     * Sets a time to wait before returning response trailers. Can be useful to validate client timeout
     * behavior. This value does not affect the time that headers or content are returned, to set those use
     * {@link #headersDelay(Duration)} or {@link #contentDelay(Duration)}.
     */
    public MockResponseBuilder trailersDelay(Duration trailersDelay) {
        this.trailersDelay = trailersDelay;
        return this;
    }

    /**
     * Sets a time to wait before returning response trailers. Can be useful to validate client timeout
     * behavior. This value does not affect the time that headers or content are returned, to set those use
     * {@link #headersDelay(int, TimeUnit)} or {@link #contentDelay(int, TimeUnit)}.
     */
    public MockResponseBuilder trailersDelay(int amount, TimeUnit timeUnit) {
        this.trailersDelay = Duration.ofMillis(timeUnit.toMillis(amount));
        return this;
    }

    /**
     * Returns a new {@link MockResponse} with the configured parameters.
     */
    public MockResponse build() {
        return new MockResponse(response, headersDelay, bodyDelay, trailersDelay);
    }
}
