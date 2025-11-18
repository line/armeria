/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.armeria.common.HttpResponseUtil.httpResponseUtilLogger;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.stream.StreamWriter;

/**
 * An {@link HttpResponse} that can have {@link HttpObject}s written to it.
 */
public interface HttpResponseWriter extends HttpResponse, StreamWriter<HttpObject> {

    /**
     * Writes the specified HTTP response and closes the stream.
     */
    default void close(AggregatedHttpResponse res) {
        boolean transferredContent = false;
        HttpData content = null;
        try {
            requireNonNull(res, "res");
            final ResponseHeaders headers = res.headers();
            content = res.content();
            if (!tryWrite(headers)) {
                return;
            }

            if (headers.status().isContentAlwaysEmpty()) {
                if (!content.isEmpty()) {
                    httpResponseUtilLogger.debug(
                            "Non-empty content found with an empty status: {}, content length: {}",
                            headers.status(), content.length());
                }
            } else if (!content.isEmpty()) {
                transferredContent = true;
                if (!tryWrite(content)) {
                    return;
                }
            }

            final HttpHeaders trailers = res.trailers();
            if (!trailers.isEmpty()) {
                @SuppressWarnings("CheckReturnValue")
                final boolean ignored = tryWrite(trailers);
            }
        } finally {
            close();
            if (!transferredContent && content != null) {
                content.close();
            }
        }
    }
}
