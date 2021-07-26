/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.util.Sampler;

/**
 * A {@link TimeoutException} raised when a response has not been received from a server within timeout.
 */
public final class ResponseTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 2556616197251937869L;

    private static final ResponseTimeoutException INSTANCE = new ResponseTimeoutException(false);

    /**
     * Returns a singleton {@link ResponseTimeoutException} or newly-created exception depending on
     * the result of {@link Sampler#isSampled(Object)} of {@link Flags#verboseExceptionSampler()}.
     */
    public static ResponseTimeoutException get() {
        return Flags.verboseExceptionSampler().isSampled(ResponseTimeoutException.class) ?
               new ResponseTimeoutException() : INSTANCE;
    }

    private ResponseTimeoutException() {}

    private ResponseTimeoutException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
