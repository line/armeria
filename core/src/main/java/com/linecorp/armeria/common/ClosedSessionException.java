/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common;

import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} raised when the connection to the remote peer has been closed unexpectedly.
 */
public final class ClosedSessionException extends RuntimeException {

    private static final long serialVersionUID = -78487475521731580L;

    private static final ClosedSessionException INSTANCE = Exceptions.clearTrace(new ClosedSessionException());

    /**
     * Returns a {@link ClosedSessionException} which may be a singleton or a new instance, depending on
     * whether {@link Exceptions#isVerbose() the verbose mode} is enabled.
     */
    public static ClosedSessionException get() {
        return Exceptions.isVerbose() ? new ClosedSessionException() : INSTANCE;
    }

    /**
     * Creates a new instance.
     */
    private ClosedSessionException() {}
}
