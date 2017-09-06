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

import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A {@link DecoratingClient} that requests and decodes HTTP encoding (e.g., gzip) that has been applied to the
 * content of a {@link HttpResponse}.
 */
public final class HttpDecodingClient extends SimpleDecoratingClient<HttpRequest, HttpResponse> {

    /**
     * Creates a new {@link HttpDecodingClient} decorator with the default encodings of 'gzip' and 'deflate'.
     */
    public static Function<Client<HttpRequest, HttpResponse>, HttpDecodingClient> newDecorator() {
        return newDecorator(
                ImmutableList.of(new GzipStreamDecoderFactory(), new DeflateStreamDecoderFactory()));
    }

    /**
     * Creates a new {@link HttpDecodingClient} decorator with the specified {@link StreamDecoderFactory}s.
     */
    public static Function<Client<HttpRequest, HttpResponse>, HttpDecodingClient>
    newDecorator(StreamDecoderFactory... decoderFactories) {
        return newDecorator(ImmutableList.copyOf(decoderFactories));
    }

    /**
     * Creates a new {@link HttpDecodingClient} decorator with the specified {@link StreamDecoderFactory}s.
     */
    public static Function<Client<HttpRequest, HttpResponse>, HttpDecodingClient> newDecorator(
            Iterable<? extends StreamDecoderFactory> decoderFactories) {
        return client -> new HttpDecodingClient(client, decoderFactories);
    }

    private final Map<String, StreamDecoderFactory> decoderFactories;
    private final String acceptEncodingHeader;

    /**
     * Creates a new instance that decorates the specified {@link Client} with the provided decoders.
     */
    private HttpDecodingClient(Client<HttpRequest, HttpResponse> delegate,
                               Iterable<? extends StreamDecoderFactory> decoderFactories) {
        super(delegate);
        this.decoderFactories = Streams.stream(decoderFactories)
                                       .collect(toImmutableMap(StreamDecoderFactory::encodingHeaderValue,
                                                               Function.identity()));
        acceptEncodingHeader = String.join(",", this.decoderFactories.keySet());
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        if (req.headers().contains(HttpHeaderNames.ACCEPT_ENCODING)) {
            // Client specified encoding, so we don't do anything automatically.
            return delegate().execute(ctx, req);
        }
        req.headers().set(HttpHeaderNames.ACCEPT_ENCODING, acceptEncodingHeader);
        HttpResponse res = delegate().execute(ctx, req);
        return new HttpDecodedResponse(res, decoderFactories);
    }
}
