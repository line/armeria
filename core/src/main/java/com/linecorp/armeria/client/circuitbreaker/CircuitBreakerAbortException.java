/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An exception which should be thrown from
 * {@link CircuitBreakerClientHandler#tryAcquireAndRequest(ClientRequestContext, Request)}
 * if a request shouldn't be handled by the respective {@link CircuitBreakerClient}.
 */
@UnstableApi
public final class CircuitBreakerAbortException extends RuntimeException {

    private static final long serialVersionUID = -6250018990113862876L;

    private static final CircuitBreakerAbortException INSTANCE =
            new CircuitBreakerAbortException(new Throwable());

    /**
     * Returns a singleton instance of {@link CircuitBreakerAbortException}.
     */
    public static CircuitBreakerAbortException get() {
        return INSTANCE;
    }

    /**
     * Creates a new instance with the specified {@link Throwable} cause.
     */
    public CircuitBreakerAbortException(Throwable t) {
        super(t);
    }
}
