/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.TimeoutException;

/**
 * A {@link TimeoutException} raised when a request has not been received from a client within timeout.
 */
public final class RequestTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 2556616197251937869L;

    private static final RequestTimeoutException INSTANCE = new RequestTimeoutException(false);

    /**
     * Returns a singleton {@link RequestTimeoutException}.
     */
    public static RequestTimeoutException get() {
        return Flags.verboseExceptionSampler().isSampled(RequestTimeoutException.class) ?
               new RequestTimeoutException() : INSTANCE;
    }

    private RequestTimeoutException() {}

    private RequestTimeoutException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
