/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder for creating instances of fixed {@link Backoff}.
 *
 * <p>This builder allows you to configure the delay duration for a fixed backoff strategy.
 * You can specify the delay in milliseconds and then create a Fixed {@link Backoff} instance
 * with the configured delay.</p>
 *
 * <p>Example usage:</p>
 *
 * <pre>
 * {@code
 * Backoff backoff = Backoff.builderForFixed()
 *     .delayMillis(500)
 *     .build();
 * }
 * </pre>
 */
@UnstableApi
public final class FixedBackoffBuilder extends AbstractBackoffBuilder<FixedBackoffBuilder> {
    static final long DEFAULT_DELAY_MILLIS = 500;

    private long delayMillis = 500;

    FixedBackoffBuilder() {}

    /**
     * Sets the delay duration in milliseconds for the Fixed {@link Backoff}.
     *
     * <p>This value determines the fixed amount of time the backoff will delay
     * before retrying an operation.</p>
     *
     * @param delayMillis the delay in milliseconds
     */
    public FixedBackoffBuilder delayMillis(long delayMillis) {
        checkArgument(delayMillis >= 0, "delayMillis: %s (expected: >= 0)", delayMillis);
        this.delayMillis = delayMillis;
        return this;
    }

    @Override
    Backoff doBuild() {
        return new FixedBackoff(delayMillis);
    }
}
