/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.throttling;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * A RateLimit Header Scheme for HTTP.
 */
@UnstableApi
public interface ThrottlingHeaders {
    /**
     * Describes
     * <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header Fields
     * for HTTP</a>. For example:
     * <pre>{@code
     * RateLimit-Limit: 10
     * RateLimit-Remaining: 1
     * RateLimit-Reset: 7
     * }</pre>
     */
    ThrottlingHeaders RATELIMIT = new ThrottlingHeadersImpl("RateLimit");

    /**
     * Describes an alternative RateLimit Header Scheme for HTTP, used by Github and Vimeo.
     * For example:
     * <pre>{@code
     * X-RateLimit-Limit: 10
     * X-RateLimit-Remaining: 1
     * X-RateLimit-Reset: 7
     * }</pre>
     */
    ThrottlingHeaders X_RATELIMIT = new ThrottlingHeadersImpl("X-RateLimit");

    /**
     * Describes another alternative RateLimit Header Scheme for HTTP, used by Twitter.
     * For example:
     * <pre>{@code
     * X-Rate-Limit-Limit: 10
     * X-Rate-Limit-Remaining: 1
     * X-Rate-Limit-Reset: 7
     * }</pre>
     */
    ThrottlingHeaders X_RATE_LIMIT = new ThrottlingHeadersImpl("X-Rate-Limit");

    /**
     * Returns the name of the "limit" throttling header for the given scheme, like "X-RateLimit-Limit".
     * This header specifies the requests quota for the given time window.
     */
    AsciiString limitHeader();

    /**
     * Returns the name of the "remaining" throttling header for the given scheme, like "X-RateLimit-Remaining".
     * This header specifies the remaining requests quota for the current time window.
     */
    AsciiString remainingHeader();

    /**
     * Returns the name of the "reset" throttling header for the given scheme, like "X-RateLimit-Reset".
     * This header specifies the time remaining in the current window. Its value defined in seconds or
     * as a timestamp.
     */
    AsciiString resetHeader();
}
