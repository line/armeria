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

package com.linecorp.armeria.client.encoding;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;

/**
 * A {@link DecoratingClient} that requests and decodes HTTP encoding (e.g., gzip) that has been applied to the
 * content of an {@link HttpResponse}.
 */
public final class DecodingClient extends SimpleDecoratingHttpClient {

    private static final Splitter ENCODING_SPLITTER = Splitter.on(',').trimResults();

    /**
     * Creates a new {@link DecodingClient} decorator with the default encodings of 'gzip' and 'deflate'.
     */
    public static Function<? super HttpClient, DecodingClient> newDecorator() {
        return builder().newDecorator();
    }

    /**
     * Creates a new {@link DecodingClient} decorator with the specified {@link StreamDecoderFactory}s.
     */
    public static Function<? super HttpClient, DecodingClient>
    newDecorator(StreamDecoderFactory... decoderFactories) {
        requireNonNull(decoderFactories, "decoderFactories");
        return newDecorator(ImmutableList.copyOf(decoderFactories));
    }

    /**
     * Creates a new {@link DecodingClient} decorator with the specified {@link StreamDecoderFactory}s.
     */
    public static Function<? super HttpClient, DecodingClient> newDecorator(
            Iterable<? extends StreamDecoderFactory> decoderFactories) {
        requireNonNull(decoderFactories, "decoderFactories");
        final List<? extends StreamDecoderFactory>
                immutableDecoderFactories = ImmutableList.copyOf(decoderFactories);
        return client -> new DecodingClient(client, immutableDecoderFactories, true, false);
    }

    /**
     * Returns a new {@link DecodingClientBuilder}.
     */
    public static DecodingClientBuilder builder() {
        return new DecodingClientBuilder();
    }

    private final Map<String, StreamDecoderFactory> decoderFactories;
    private final String acceptEncodingHeader;
    private final boolean autoFillAcceptEncoding;
    private final boolean strictContentEncoding;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient} with the provided decoders.
     */
    DecodingClient(HttpClient delegate,
                   Iterable<? extends StreamDecoderFactory> decoderFactories,
                   boolean autoFillAcceptEncoding,
                   boolean strictContentEncoding) {
        super(delegate);
        this.decoderFactories = Streams.stream(decoderFactories)
                                       .collect(toImmutableMap(StreamDecoderFactory::encodingHeaderValue,
                                                               Function.identity()));
        acceptEncodingHeader = String.join(",", this.decoderFactories.keySet());
        this.autoFillAcceptEncoding = autoFillAcceptEncoding;
        this.strictContentEncoding = strictContentEncoding;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        Map<String, StreamDecoderFactory> decoderFactories = this.decoderFactories;

        if (autoFillAcceptEncoding) {
            if (req.headers().contains(HttpHeaderNames.ACCEPT_ENCODING)) {
                // Client specified encoding, so we don't do anything automatically.
                return unwrap().execute(ctx, req);
            }

            req = updateAcceptEncoding(ctx, req, acceptEncodingHeader);
            return executeAndDecodeResponse(ctx, req, decoderFactories);
        }

        // Respect user-defined accept-encoding.
        final String acceptEncoding = req.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
        if (Strings.isNullOrEmpty(acceptEncoding)) {
            // No accept-encoding is specified.
            return unwrap().execute(ctx, req);
        }

        final List<String> encodings = ImmutableList.copyOf(ENCODING_SPLITTER.split(acceptEncoding));
        final ImmutableMap.Builder<String, StreamDecoderFactory> factoryBuilder =
                ImmutableMap.builderWithExpectedSize(encodings.size());

        for (String encoding : encodings) {
            final StreamDecoderFactory factory = decoderFactories.get(encoding);
            if (factory != null) {
                factoryBuilder.put(factory.encodingHeaderValue(), factory);
            }
        }

        final Map<String, StreamDecoderFactory> availableFactories = factoryBuilder.build();
        if (availableFactories.isEmpty()) {
            // Unsupported encoding.
            req = updateAcceptEncoding(ctx, req, null);
            return unwrap().execute(ctx, req);
        }

        if (encodings.size() != availableFactories.size()) {
            // Use only supported encodings.
            final String acceptEncodingHeader = String.join(",", availableFactories.keySet());
            req = updateAcceptEncoding(ctx, req, acceptEncodingHeader);
        }
        decoderFactories = availableFactories;

        return executeAndDecodeResponse(ctx, req, decoderFactories);
    }

    private HttpDecodedResponse executeAndDecodeResponse(
            ClientRequestContext ctx, HttpRequest req,
            Map<String, StreamDecoderFactory> decoderFactories) throws Exception {
        final HttpResponse res = unwrap().execute(ctx, req);
        return new HttpDecodedResponse(res, decoderFactories, ctx.alloc(), strictContentEncoding);
    }

    private static HttpRequest updateAcceptEncoding(ClientRequestContext ctx, HttpRequest req,
                                                    @Nullable String acceptEncoding) {
        final RequestHeadersBuilder headersBuilder;
        if (acceptEncoding == null) {
            headersBuilder = req.headers().toBuilder()
                                .removeAndThen(HttpHeaderNames.ACCEPT_ENCODING);
        } else {
            headersBuilder = req.headers().toBuilder()
                                .set(HttpHeaderNames.ACCEPT_ENCODING, acceptEncoding);
        }

        final HttpRequest updated = req.withHeaders(headersBuilder);
        ctx.updateRequest(updated);
        return updated;
    }
}
