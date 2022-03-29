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

import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
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
 */
public final class EncodingService extends SimpleDecoratingHttpService {

    private final Predicate<MediaType> encodableContentTypePredicate;
    private final Predicate<? super RequestHeaders> encodableRequestHeadersPredicate;
    private final long minBytesToForceChunkedAndEncoding;

    /**
     * Returns a new {@link EncodingServiceBuilder}.
     */
    public static EncodingServiceBuilder builder() {
        return new EncodingServiceBuilder();
    }

    /**
     * Returns a new {@link HttpService} decorator.
     */
    public static Function<? super HttpService, EncodingService> newDecorator() {
        return builder().newDecorator();
    }

    /**
     * Creates a new instance.
     */
    EncodingService(HttpService delegate,
                    Predicate<MediaType> encodableContentTypePredicate,
                    Predicate<? super RequestHeaders> encodableRequestHeadersPredicate,
                    long minBytesToForceChunkedAndEncoding) {
        super(delegate);
        this.encodableContentTypePredicate = encodableContentTypePredicate;
        this.encodableRequestHeadersPredicate = encodableRequestHeadersPredicate;
        this.minBytesToForceChunkedAndEncoding = minBytesToForceChunkedAndEncoding;
    }

    @Override
    public ExchangeType exchangeType(RequestHeaders headers, Route route) {
        // Avoid aggregation to preserve the compressed chunks.
        return ExchangeType.BIDI_STREAMING;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpEncodingType encodingType = HttpEncoders.getWrapperForRequest(req);
        final HttpResponse delegateResponse = unwrap().serve(ctx, req);
        if (encodingType == null || !encodableRequestHeadersPredicate.test(req.headers())) {
            return delegateResponse;
        }
        return new HttpEncodedResponse(
                delegateResponse,
                encodingType,
                encodableContentTypePredicate,
                minBytesToForceChunkedAndEncoding);
    }
}
