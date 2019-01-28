/*
 * Copyright 2018 LINE Corporation
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
 * A {@link RuntimeException} raised when it is certain that a request has not been handled by a server and
 * thus can be retried safely. This exception is usually raised when a server sent an HTTP/2 GOAWAY frame with
 * the {@code lastStreamId} less than the stream ID of the request.
 *
 * @see <a href="https://httpwg.org/specs/rfc7540.html#GOAWAY">Section 6.8, RFC7540</a>
 */
public final class UnprocessedRequestException extends RuntimeException {

    private static final long serialVersionUID = 4679512839715213302L;

    private static final UnprocessedRequestException INSTANCE = new UnprocessedRequestException(false);

    /**
     * Returns an {@link UnprocessedRequestException} which may be a singleton or a new instance, depending on
     * whether {@linkplain Flags#verboseExceptions() the verbose exception mode} is enabled.
     */
    public static UnprocessedRequestException get() {
        return Flags.verboseExceptions() ? new UnprocessedRequestException() : INSTANCE;
    }

    private UnprocessedRequestException() {}

    private UnprocessedRequestException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
