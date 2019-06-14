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

package com.linecorp.armeria.client.limit;

/**
 * A {@link RuntimeException} raised when {@link ConcurrencyLimitingClient#numActiveRequests()} exceeds
 * the configured {@code maxConcurrency}.
 */
public final class ConcurrencyLimitingExceedException extends RuntimeException {

    private static final long serialVersionUID = 2380973537286999696L;

    private static final ConcurrencyLimitingExceedException INSTANCE = new ConcurrencyLimitingExceedException();

    /**
     * Returns a singleton {@link ConcurrencyLimitingExceedException}.
     */
    public static ConcurrencyLimitingExceedException get() {
        return INSTANCE;
    }

    private ConcurrencyLimitingExceedException() {
        super(null, null, false, false);
    }
}
