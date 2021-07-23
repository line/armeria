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

package com.linecorp.armeria.server.encoding;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * A {@link DecoratingService} that requests and decodes HTTP encoding (e.g., gzip) that has been applied to the
 * content of an {@link HttpRequest}.
 */
public final class DecodingService extends SimpleDecoratingHttpService {

    /**
     * Creates a new {@link DecodingService} decorator with the default encodings of 'gzip', 'deflate' and 'brotli'.
     */
    public static Function<? super HttpService, DecodingService> newDecorator() {
        return newDecorator(ImmutableList.of(StreamDecoderFactory.gzip(), StreamDecoderFactory.deflate(),
                                             StreamDecoderFactory.brotli()));
    }

    /**
     * Creates a new {@link DecodingService} decorator with the specified {@link StreamDecoderFactory}s.
     */
    public static Function<? super HttpService, DecodingService> newDecorator(
            StreamDecoderFactory... decoderFactories) {
        requireNonNull(decoderFactories, "decoderFactories");
        return newDecorator(ImmutableList.copyOf(decoderFactories));
    }

    /**
     * Creates a new {@link DecodingService} decorator with the specified {@link StreamDecoderFactory}s.
     */
    public static Function<? super HttpService, DecodingService> newDecorator(
            Iterable<? extends StreamDecoderFactory> decoderFactories) {
        requireNonNull(decoderFactories, "decoderFactories");
        final List<? extends StreamDecoderFactory> immutableDecoderFactories =
                ImmutableList.copyOf(decoderFactories);
        return delegate -> new DecodingService(delegate, immutableDecoderFactories);
    }

    private final Map<String, StreamDecoderFactory> decoderFactories;

    /**
     * Creates a new instance that decorates the specified {@link HttpService} with the provided decoders.
     */
    private DecodingService(HttpService delegate, Iterable<? extends StreamDecoderFactory> decoderFactories) {
        super(delegate);
        this.decoderFactories = Streams.stream(decoderFactories)
                                       .collect(toImmutableMap(StreamDecoderFactory::encodingHeaderValue,
                                                               Function.identity()));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final String contentEncoding = req.headers().get(HttpHeaderNames.CONTENT_ENCODING);
        if (contentEncoding == null) {
            // If the request does not contain [Content-Encoding] header,
            // pass the request to the next service.
            return unwrap().serve(ctx, req);
        }
        final StreamDecoderFactory decoderFactory = decoderFactories.get(Ascii.toLowerCase(contentEncoding));
        if (decoderFactory != null) {
            final HttpDecodedRequest decodedRequest = new HttpDecodedRequest(req, decoderFactory, ctx.alloc());
            ctx.updateRequest(decodedRequest);
            return unwrap().serve(ctx, decodedRequest);
        } else {
            // The request contain [Content-Encoding] but not exist appropriate decoder,
            // pass the request to the next service.
            return unwrap().serve(ctx, req);
        }
    }
}
