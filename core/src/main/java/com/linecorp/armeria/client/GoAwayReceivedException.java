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

/**
 * A {@link RuntimeException} raised when a server sent an
 * <a href="https://httpwg.org/specs/rfc7540.html#GOAWAY">HTTP/2 GOAWAY frame</a> with
 * the {@code lastStreamId} less then the stream ID of the request.
 */
public final class GoAwayReceivedException extends RuntimeException {

    private static final long serialVersionUID = -7167601309699030853L;

    private static final GoAwayReceivedException INSTANCE = new GoAwayReceivedException();

    /**
     * Returns a singleton {@link GoAwayReceivedException}.
     */
    public static GoAwayReceivedException get() {
        return INSTANCE;
    }

    private GoAwayReceivedException() {
        super(null, null, false, false);
    }
}
