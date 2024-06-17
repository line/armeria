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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

abstract class AbstractResponseAssert<T> {
    private final T actual;
    private final TestHttpResponse response;

    AbstractResponseAssert(T actual, TestHttpResponse response) {
        requireNonNull(actual, "actual");
        requireNonNull(response, "response");
        this.actual = actual;
        this.response = response;
    }

    T actual() {
        return actual;
    }

    TestHttpResponse response() {
        return response;
    }

    /**
     * Asserts that the actual {@code actual} is equal to the given one.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse isEqualTo(T expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual());
        return response();
    }
}
