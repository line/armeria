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

package com.linecorp.armeria.testing.junit.server.mock;

import java.time.Duration;

import com.linecorp.armeria.common.AggregatedHttpResponse;

/**
 * A mock response to return to a request made to {@link MockWebServerExtension}.
 */
public class MockResponse {

    /**
     * Returns a {@link MockResponse} which will return the provided {@link AggregatedHttpResponse}.
     */
    public static MockResponse of(AggregatedHttpResponse response) {
        return builder(response).build();
    }

    /**
     * Returns a {@link MockResponseBuilder} which will return the provided {@link AggregatedHttpResponse} and
     * can be additionally configured.
     */
    public static MockResponseBuilder builder(AggregatedHttpResponse response) {
        return new MockResponseBuilder(response);
    }

    private final AggregatedHttpResponse response;
    private final Duration delay;

    MockResponse(AggregatedHttpResponse response, Duration delay) {
        this.response = response;
        this.delay = delay;
    }

    /**
     * Returns the {@link AggregatedHttpResponse} of this {@link MockResponse}.
     */
    public AggregatedHttpResponse response() {
        return response;
    }

    /**
     * Returns the time to wait before returning the {@link AggregatedHttpResponse} of this
     * {@link MockResponse}.
     */
    public Duration delay() {
        return delay;
    }
}
