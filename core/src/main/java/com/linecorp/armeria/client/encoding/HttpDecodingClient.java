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

import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A {@link DecoratingClient} that requests and decodes HTTP encoding (e.g., gzip) that has been applied to the
 * content of an {@link HttpResponse}.
 *
 * @deprecated Use {@link DecodingClient}.
 */
@Deprecated
public final class HttpDecodingClient extends DecodingClient {

    /**
     * Creates a new {@link DecodingClient} decorator with the default encodings of 'gzip' and 'deflate'.
     *
     * @deprecated Use {@link DecodingClient#newDecorator()}.
     */
    @Deprecated
    public static Function<? super HttpClient, DecodingClient> newDecorator() {
        return newDecorator(ImmutableList.of(StreamDecoderFactory.gzip(), StreamDecoderFactory.deflate()));
    }

    /**
     * Creates a new {@link DecodingClient} decorator with the specified {@link StreamDecoderFactory}s.
     *
     * @deprecated Use {@link DecodingClient#newDecorator(StreamDecoderFactory...)}.
     */
    @Deprecated
    public static Function<? super HttpClient, DecodingClient>
    newDecorator(StreamDecoderFactory... decoderFactories) {
        return newDecorator(ImmutableList.copyOf(decoderFactories));
    }

    /**
     * Creates a new {@link DecodingClient} decorator with the specified {@link StreamDecoderFactory}s.
     *
     * @deprecated Use {@link DecodingClient#newDecorator(Iterable)}.
     */
    @Deprecated
    public static Function<? super HttpClient, DecodingClient> newDecorator(
            Iterable<? extends StreamDecoderFactory> decoderFactories) {
        return client -> new DecodingClient(client, decoderFactories);
    }

    /**
     * Creates a new instance that decorates the specified {@link HttpClient} with the provided decoders.
     */
    HttpDecodingClient(HttpClient delegate,
                       Iterable<? extends StreamDecoderFactory> decoderFactories) {
        super(delegate, decoderFactories);
    }
}
