/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.util.Sampler;

/**
 * A {@link TimeoutException} raised when a request is not sent from {@link ConcurrencyLimitingClient}
 * due to timeout.
 */
public final class ConcurrencyLimitTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 8752054852017165156L;

    private static final ConcurrencyLimitTimeoutException INSTANCE =
            new ConcurrencyLimitTimeoutException(false);

    /**
     * Returns a singleton {@link ConcurrencyLimitTimeoutException} or newly-created exception depending on
     * the result of {@link Sampler#isSampled(Object)} of {@link Flags#verboseExceptionSampler()}.
     */
    public static ConcurrencyLimitTimeoutException get() {
        return Flags.verboseExceptionSampler().isSampled(ConcurrencyLimitTimeoutException.class) ?
               new ConcurrencyLimitTimeoutException() : INSTANCE;
    }

    private ConcurrencyLimitTimeoutException() {}

    private ConcurrencyLimitTimeoutException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
