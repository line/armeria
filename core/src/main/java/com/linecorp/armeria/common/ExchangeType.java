/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represents whether to stream an {@link HttpRequest} or {@link HttpResponse}.
 */
@UnstableApi
public enum ExchangeType {
    /**
     * No streaming. A {@link HttpRequest} and a {@link HttpResponse} will be buffered
     * when they are sent or received.
     */
    UNARY(false, false),
    /**
     * A streaming {@link HttpRequest} with a non-streaming {@link HttpResponse}.
     * The {@link HttpResponse} will be buffered when it is sent or received.
     */
    REQUEST_STREAMING(true, false),
    /**
     * A non-streaming {@link HttpRequest} with a streaming {@link HttpResponse}.
     * The {@link HttpRequest} will be buffered when it is sent or received.
     */
    RESPONSE_STREAMING(false, true),
    /**
     * Bidirectional streaming.
     * Neither a {@link HttpRequest} nor a {@link HttpResponse} is buffered.
     */
    BIDI_STREAMING(true, true);

    private final boolean requestStreaming;
    private final boolean responseStreaming;

    ExchangeType(boolean requestStreaming, boolean responseStreaming) {
        this.requestStreaming = requestStreaming;
        this.responseStreaming = responseStreaming;
    }

    /**
     * Returns whether to support request streaming.
     */
    public boolean isRequestStreaming() {
        return requestStreaming;
    }

    /**
     * Returns whether to support response streaming.
     */
    public boolean isResponseStreaming() {
        return responseStreaming;
    }
}
