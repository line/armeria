/*
 * Copyright 2023 LINE Corporation
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
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link TimeoutException} triggered when failed to create session with the desired
 * {@link SessionProtocol} with a server.
 */
public final class SessionCreationTimeoutException extends TimeoutException {

    private static final long serialVersionUID = -7767888180307072426L;

    private final SessionProtocol protocol;

    /**
     * Creates a new instance with the specified {@link SessionProtocol}.
     */
    public SessionCreationTimeoutException(SessionProtocol protocol, @Nullable String reason) {
        super("protocol: " + requireNonNull(protocol, "protocol") + ", reason: " + reason);
        this.protocol = protocol;
    }

    /**
     * Returns the {@link SessionProtocol}.
     */
    public SessionProtocol protocol() {
        return protocol;
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
