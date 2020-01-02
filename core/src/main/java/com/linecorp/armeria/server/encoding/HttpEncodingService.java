/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.encoding;

import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to apply HTTP encoding (e.g., gzip) to an {@link HttpService}.
 * HTTP encoding will be applied if:
 * <ul>
 *     <li>the client specifies it</li>
 *     <li>the response content type is encodable</li>
 *     <li>the request headers are acceptable</li>
 *     <li>the response either has no fixed content length or the length is larger than 1KB</li>
 * </ul>
 *
 * @deprecated Use {@link EncodingService}.
 */
@Deprecated
public final class HttpEncodingService extends EncodingService {

    /**
     * Creates a new {@link SimpleDecoratingHttpService} that HTTP-encodes the response data published from
     * {@code delegate}. Encoding will be applied when the client supports it, the response content type
     * is a common web text format, and the response either has variable content length or a length greater
     * than 1024.
     *
     * @deprecated Use {@link EncodingService}.
     */
    @Deprecated
    public HttpEncodingService(HttpService delegate) {
        super(delegate);
    }

    /**
     * Creates a new {@link SimpleDecoratingHttpService} that HTTP-encodes the response data published from
     * {@code delegate}. Encoding will be applied when the client supports it, the response content type
     * passes the supplied {@code encodableContentTypePredicate} and the response either has variable
     * content length or a length greater than {@code minBytesToForceChunkedAndEncoding}.
     *
     * @deprecated Use {@link EncodingService}.
     */
    @Deprecated
    public HttpEncodingService(HttpService delegate,
                               Predicate<MediaType> encodableContentTypePredicate,
                               int minBytesToForceChunkedAndEncoding) {
        super(delegate, encodableContentTypePredicate, minBytesToForceChunkedAndEncoding);
    }

    /**
     * Creates a new {@link SimpleDecoratingHttpService} that HTTP-encodes the response data published from
     * {@code delegate}. Encoding will be applied when the client supports it, the response content type
     * passes the supplied {@code encodableContentTypePredicate}, the request headers passes the supplied
     * {@code encodableRequestHeadersPredicate} and the response either has variable content length or a length
     * greater than {@code minBytesToForceChunkedAndEncoding}.
     *
     * @deprecated Use {@link EncodingService}.
     */
    @Deprecated
    public HttpEncodingService(HttpService delegate,
                               Predicate<MediaType> encodableContentTypePredicate,
                               Predicate<HttpHeaders> encodableRequestHeadersPredicate,
                               long minBytesToForceChunkedAndEncoding) {
        super(delegate, encodableContentTypePredicate, encodableRequestHeadersPredicate,
              minBytesToForceChunkedAndEncoding);
    }
}
