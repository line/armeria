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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RoutingContext;
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
     * Map from {@link HttpHeaderNames#ACCEPT_ENCODING} to the {@link StreamEncoderFactory} that will
     * get used for that encoding.
     */
    private final Map<String, StreamEncoderFactory> encoderFactories;

    /**
     * Returns a new {@link EncodingServiceBuilder}.
     */
    public static EncodingServiceBuilder builder() {
        return new EncodingServiceBuilder();
    }

    /**
     * Returns a decorator that decorates an {@link HttpService} with an
     * {@link EncodingService}. The {@link EncodingService} is configured to use all
     * {@link StreamEncoderFactory}s available through {@link StreamEncoderFactory#all()}.
     * A {@link StreamEncoderFactory} is selected whenever their
     * {@link StreamEncoderFactory#encodingHeaderValue()} has the highest weight in the
     * {@link HttpHeaderNames#ACCEPT_ENCODING} header. See
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.4">RFC 7231</a> for details.
     *
     * <p>
     *  All other properties of the returned {@link EncodingService}
     *  are set to their default values by {@link EncodingServiceBuilder}.
     * </p>
     *
     * @see StreamEncoderFactory#encodingHeaderValue()
     */
    public static Function<? super HttpService, EncodingService> newDecorator() {
        return builder().newDecorator();
    }

    /**
     * Returns a decorator that decorates an {@link HttpService} with an
     * {@link EncodingService}. The {@link EncodingService} is configured to use the
     * provided {@link StreamEncoderFactory}s whenever their {@link StreamEncoderFactory#encodingHeaderValue()}
     * has the highest weight in the {@link HttpHeaderNames#ACCEPT_ENCODING} header. See
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.4">RFC 7231</a> for details.
     *
     * <p>
     *  All other properties of the returned {@link EncodingService}
     *  are set to their default values by {@link EncodingServiceBuilder}.
     * </p>
     *
     * @param encoderFactories the {@link StreamEncoderFactory}s that should each be used for the encoding
     *
     * @see StreamEncoderFactory#encodingHeaderValue()
     */
    public static Function<? super HttpService, EncodingService>
    newDecorator(StreamEncoderFactory... encoderFactories) {
        requireNonNull(encoderFactories, "encoderFactories");
        return newDecorator(ImmutableList.copyOf(encoderFactories));
    }

    /**
     * Returns a decorator that decorates an {@link HttpService} with an
     * {@link EncodingService}. The {@link EncodingService} is configured to use the
     * provided {@link StreamEncoderFactory}s whenever their {@link StreamEncoderFactory#encodingHeaderValue()}
     * has the highest weight in the {@link HttpHeaderNames#ACCEPT_ENCODING} header. See
     * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.4">RFC 7231</a> for details.
     *
     * <p>
     *  All other properties of the returned {@link EncodingService}
     *  are set to their default values by {@link EncodingServiceBuilder}.
     * </p>
     *
     * @param encoderFactories the {@link StreamEncoderFactory}s that should each be used for the encoding
     *
     * @see StreamEncoderFactory#encodingHeaderValue()
     */
    public static Function<? super HttpService, EncodingService>
    newDecorator(Iterable<? extends StreamEncoderFactory> encoderFactories) {
        requireNonNull(encoderFactories, "encoderFactories");
        return builder().encoderFactories(encoderFactories).newDecorator();
    }

    /**
     * Creates a new instance.
     */
    EncodingService(HttpService delegate,
                    Predicate<MediaType> encodableContentTypePredicate,
                    Predicate<? super RequestHeaders> encodableRequestHeadersPredicate,
                    long minBytesToForceChunkedAndEncoding
    ) {
        this(delegate, StreamEncoderFactory.all(), encodableContentTypePredicate,
             encodableRequestHeadersPredicate, minBytesToForceChunkedAndEncoding);
    }

    /**
     * Creates a new instance.
     */
    EncodingService(HttpService delegate,
                    Iterable<? extends StreamEncoderFactory> encoderFactories,
                    Predicate<MediaType> encodableContentTypePredicate,
                    Predicate<? super RequestHeaders> encodableRequestHeadersPredicate,
                    long minBytesToForceChunkedAndEncoding
    ) {
        super(delegate);
        this.encoderFactories = Streams.stream(encoderFactories)
                                       .collect(toImmutableMap(StreamEncoderFactory::encodingHeaderValue,
                                                               Function.identity()));
        this.encodableContentTypePredicate = encodableContentTypePredicate;
        this.encodableRequestHeadersPredicate = encodableRequestHeadersPredicate;
        this.minBytesToForceChunkedAndEncoding = minBytesToForceChunkedAndEncoding;
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        // Avoid aggregation to preserve the compressed chunks.
        return ExchangeType.BIDI_STREAMING;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final StreamEncoderFactory encoderFactory = HttpEncoders.determineEncoder(encoderFactories,
                                                                                  req.headers()
        );
        final HttpResponse delegateResponse = unwrap().serve(ctx, req);
        if (encoderFactory == null || !encodableRequestHeadersPredicate.test(req.headers())) {
            return delegateResponse;
        }
        return new HttpEncodedResponse(delegateResponse, encoderFactory, encodableContentTypePredicate,
                                       ctx.alloc(), minBytesToForceChunkedAndEncoding);
    }
}
