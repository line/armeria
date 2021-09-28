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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An exception triggered when failed to negotiate the desired {@link SessionProtocol} with a server.
 */
public final class SessionProtocolNegotiationException extends RuntimeException {

    private static final long serialVersionUID = 5788454584691399858L;

    private final SessionProtocol expected;
    @Nullable
    private final SessionProtocol actual;

    /**
     * Creates a new instance with the specified expected {@link SessionProtocol}.
     */
    public SessionProtocolNegotiationException(SessionProtocol expected, @Nullable String reason) {
        super("expected: " + requireNonNull(expected, "expected") + ", reason: " + reason);
        this.expected = expected;
        actual = null;
    }

    /**
     * Creates a new instance with the specified expected and actual {@link SessionProtocol}s.
     */
    public SessionProtocolNegotiationException(SessionProtocol expected,
                                               @Nullable SessionProtocol actual, @Nullable String reason) {

        super("expected: " + requireNonNull(expected, "expected") +
              ", actual: " + requireNonNull(actual, "actual") + ", reason: " + reason);
        this.expected = expected;
        this.actual = actual;
    }

    /**
     * Returns the expected {@link SessionProtocol}.
     */
    public SessionProtocol expected() {
        return expected;
    }

    /**
     * Returns the actual {@link SessionProtocol}.
     *
     * @return the actual {@link SessionProtocol}, or {@code null} if failed to determine the protocol.
     */
    @Nullable
    public SessionProtocol actual() {
        return actual;
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
