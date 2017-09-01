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
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link TimeoutException} raised when a response has not been received from a server within timeout.
 */
public final class ResponseTimeoutException extends TimeoutException {

    private static final long serialVersionUID = 2556616197251937869L;

    private static final ResponseTimeoutException INSTANCE =
            Exceptions.clearTrace(new ResponseTimeoutException());

    /**
     * Returns a {@link ResponseTimeoutException} which may be a singleton or a new instance, depending on
     * whether {@link Flags#verboseExceptions() the verbose exception mode} is enabled.
     */
    public static ResponseTimeoutException get() {
        return Flags.verboseExceptions() ? new ResponseTimeoutException() : INSTANCE;
    }

    /**
     * Creates a new instance.
     */
    private ResponseTimeoutException() {}
}
