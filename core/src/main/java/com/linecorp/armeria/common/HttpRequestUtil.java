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
package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_LENGTH;

final class HttpRequestUtil {

    static RequestHeaders maybeModifyContentLength(RequestHeaders headers, HttpData content) {
        if (headers.isContentLengthUnknown()) {
            // A streaming content.
            return headers;
        }

        if (headers.contentLength() == content.length()) {
            return headers;
        }

        if (content.isEmpty()) {
            if (headers.contentLength() <= 0) {
                return headers;
            }
            return headers.toBuilder().removeAndThen(CONTENT_LENGTH).build();
        }

        return headers.toBuilder().contentLength(content.length()).build();
    }

    private HttpRequestUtil() {}
}
