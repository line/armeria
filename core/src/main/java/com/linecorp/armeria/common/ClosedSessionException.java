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
package com.linecorp.armeria.common;

/**
 * A {@link RuntimeException} raised when the connection to the remote peer has been closed unexpectedly.
 */
public final class ClosedSessionException extends RuntimeException {

    private static final long serialVersionUID = -78487475521731580L;

    private static final ClosedSessionException INSTANCE = new ClosedSessionException(false);

    /**
     * Returns a {@link ClosedSessionException} which may be a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static ClosedSessionException get() {
        return Flags.verboseExceptionSampler().isSampled(ClosedSessionException.class) ?
               new ClosedSessionException() : INSTANCE;
    }

    /**
     * Returns a {@link ClosedSessionException} with a cause.
     */
    public static ClosedSessionException get(Throwable cause) {
        return Flags.verboseExceptionSampler().isSampled(ClosedSessionException.class) ?
               new ClosedSessionException(cause) : INSTANCE;
    }

    private ClosedSessionException() {}

    private ClosedSessionException(Throwable cause) {
        super(cause);
    }

    private ClosedSessionException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
