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

import static java.util.Objects.requireNonNull;

import java.util.function.Predicate;
import java.util.stream.Stream;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

/**
 * Decorates a {@link Service} to apply HTTP encoding (e.g., gzip) to an {@link HttpService}.
 * HTTP encoding will be applied if:
 * <ul>
 *     <li>the client specifies it</li>
 *     <li>the response content type is a reasonable type to encode</li>
 *     <li>the request headers are also acceptable</li>
 *     <li>the response either has no fixed content length or the length is larger than 1KB</li>
 * </ul>
 */
public class HttpEncodingService
        extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    private static final Predicate<MediaType> DEFAULT_ENCODABLE_CONTENT_TYPE_PREDICATE =
            contentType -> Stream.of(MediaType.ANY_TEXT_TYPE,
                                     MediaType.APPLICATION_XML_UTF_8,
                                     MediaType.JAVASCRIPT_UTF_8,
                                     MediaType.JSON_UTF_8)
                                 .anyMatch(contentType::is);

    private static final Predicate<HttpHeaders> DEFAULT_ENCODABLE_REQUEST_HEADERS_PREDICATE =
            headers -> true;

    private static final int DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING = 1024;

    private final Predicate<MediaType> encodableContentTypePredicate;
    private final Predicate<HttpHeaders> encodableRequestHeadersPredicate;
    private final long minBytesToForceChunkedAndEncoding;

    /**
     * Creates a new {@link DecoratingService} that HTTP-encodes the response data published from
     * {@code delegate}. Encoding will be applied when the client supports it, the response content type
     * is a common web text format, and the response either has variable content length or a length greater
     * than 1024.
     */
    public HttpEncodingService(Service<HttpRequest, HttpResponse> delegate) {
        this(delegate, DEFAULT_ENCODABLE_CONTENT_TYPE_PREDICATE,
             DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING);
    }

    /**
     * Creates a new {@link DecoratingService} that HTTP-encodes the response data published from
     * {@code delegate}. Encoding will be applied when the client supports it, the response content type
     * passes the supplied {@code encodableContentTypePredicate} and the response either has variable
     * content length or a length greater than {@code minBytesToForceChunkedAndEncoding}.
     */
    public HttpEncodingService(Service<HttpRequest, HttpResponse> delegate,
                               Predicate<MediaType> encodableContentTypePredicate,
                               int minBytesToForceChunkedAndEncoding) {
        this(delegate, encodableContentTypePredicate,
             DEFAULT_ENCODABLE_REQUEST_HEADERS_PREDICATE, minBytesToForceChunkedAndEncoding);
    }

    /**
     * Creates a new {@link DecoratingService} that HTTP-encodes the response data published from
     * {@code delegate}. Encoding will be applied when the client supports it, the response content type
     * passes the supplied {@code encodableContentTypePredicate}, the request headers passes the supplied
     * {@code encodableRequestHeadersPredicate} and the response either has variable content length or a length
     * greater than {@code minBytesToForceChunkedAndEncoding}.
     */
    public HttpEncodingService(Service<HttpRequest, HttpResponse> delegate,
                               Predicate<MediaType> encodableContentTypePredicate,
                               Predicate<HttpHeaders> encodableRequestHeadersPredicate,
                               long minBytesToForceChunkedAndEncoding) {
        super(delegate);
        this.encodableContentTypePredicate = requireNonNull(encodableContentTypePredicate,
                                                            "encodableContentTypePredicate");
        this.encodableRequestHeadersPredicate = requireNonNull(encodableRequestHeadersPredicate,
                                                               "encodableRequestHeadersPredicate");
        this.minBytesToForceChunkedAndEncoding = validateMinBytesToForceChunkedAndEncoding(
                minBytesToForceChunkedAndEncoding);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpEncodingType encodingType = HttpEncoders.getWrapperForRequest(req);
        final HttpResponse delegateResponse = delegate().serve(ctx, req);
        if (encodingType == null || !encodableRequestHeadersPredicate.test(req.headers())) {
            return delegateResponse;
        }
        return new HttpEncodedResponse(
                delegateResponse,
                encodingType,
                encodableContentTypePredicate,
                minBytesToForceChunkedAndEncoding);
    }

    static long validateMinBytesToForceChunkedAndEncoding(long minBytesToForceChunkedAndEncoding) {
        if (minBytesToForceChunkedAndEncoding <= 0) {
            throw new IllegalArgumentException("minBytesToForceChunkedAndEncoding must be greater than 0.");
        }
        return minBytesToForceChunkedAndEncoding;
    }
}
