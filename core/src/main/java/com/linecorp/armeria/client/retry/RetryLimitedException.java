/*
 * Copyright 2025 LY Corporation
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

import com.linecorp.armeria.common.Flags;

/**
 * An exception thrown when a retry is limited by a {@link RetryLimiter}.
 */
public final class RetryLimitedException extends RuntimeException {

    private static final long serialVersionUID = 7203512016805562689L;

    private static final RetryLimitedException INSTANCE = new RetryLimitedException(false);

    /**
     * Returns an instance of {@link RetryLimitedException} sampled by {@link Flags#verboseExceptionSampler()}.
     */
    public static RetryLimitedException of() {
        return isSampled() ? new RetryLimitedException(true) : INSTANCE;
    }

    private RetryLimitedException(boolean enableSuppression) {
        super(null, null, enableSuppression, isSampled());
    }

    private static boolean isSampled() {
        return Flags.verboseExceptionSampler().isSampled(RetryLimitedException.class);
    }
}
