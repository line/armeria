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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isContentAlwaysEmptyWithValidation;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
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
        @Nullable HttpData content = null;
        try {
            requireNonNull(res, "res");

            final ResponseHeaders headers = res.headers();
            final HttpStatus status = headers.status();
            content = res.content();
            final boolean contentAlwaysEmpty = isContentAlwaysEmptyWithValidation(status, content);

            if (!tryWrite(headers)) {
                return;
            }

            // Add content if not empty.
            if (!contentAlwaysEmpty && !content.isEmpty()) {
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
