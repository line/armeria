/*
 * Copyright 2019 LINE Corporation
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

/**
 * A {@link RuntimeException} raised when a server set
 * HTTP/2 <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-5.1.2">{@code MAX_CONCURRENT_STREAMS}</a>
 * to 0, which means a client can't send anything.
 */
public final class RefusedStreamException extends RuntimeException {

    private static final long serialVersionUID = 4865362114731585884L;

    private static final RefusedStreamException INSTANCE = new RefusedStreamException(false);

    /**
     * Returns a singleton {@link RefusedStreamException}.
     */
    public static RefusedStreamException get() {
        return Flags.verboseExceptionSampler().isSampled(RefusedStreamException.class) ?
               new RefusedStreamException() : INSTANCE;
    }

    private RefusedStreamException() {}

    private RefusedStreamException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
