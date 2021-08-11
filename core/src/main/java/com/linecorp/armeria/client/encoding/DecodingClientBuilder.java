/*
 * Copyright 2021 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;

/**
 * A builder class for {@link DecodingClient}.
 */
public final class DecodingClientBuilder {

    private List<StreamDecoderFactory> decoderFactories = ImmutableList.of(StreamDecoderFactory.brotli(),
                                                                           StreamDecoderFactory.gzip(),
                                                                           StreamDecoderFactory.deflate());

    private boolean autoFillAcceptEncoding = true;
    private boolean strictContentEncoding;

    DecodingClientBuilder() {}

    /**
     * Sets the specified {@link StreamDecoderFactory}s.
     * If not specified, {@link StreamDecoderFactory#gzip()}, {@link StreamDecoderFactory#deflate()} and
     * {@link StreamDecoderFactory#brotli()} are used by default.
     */
    public DecodingClientBuilder decoderFactories(StreamDecoderFactory... decoderFactories) {
        requireNonNull(decoderFactories, "decoderFactories");
        return decoderFactories(ImmutableList.copyOf(decoderFactories));
    }

    /**
     * Sets the specified {@link StreamDecoderFactory}s.
     * If not specified, {@link StreamDecoderFactory#gzip()}, {@link StreamDecoderFactory#deflate()} and
     * {@link StreamDecoderFactory#brotli()} are used by default.
     */
    public DecodingClientBuilder decoderFactories(Iterable<? extends StreamDecoderFactory> decoderFactories) {
        requireNonNull(decoderFactories, "decoderFactories");
        this.decoderFactories = ImmutableList.copyOf(decoderFactories);
        return this;
    }

    /**
     * Automatically fills possible {@link HttpHeaderNames#ACCEPT_ENCODING}s specified in
     * {@link #decoderFactories(StreamDecoderFactory...)} if an {@link HttpHeaderNames#ACCEPT_ENCODING} is not
     * set in {@link RequestHeaders}.
     * This option is enabled by default.
     */
    public DecodingClientBuilder autoFillAcceptEncoding(boolean autoFillAcceptEncoding) {
        this.autoFillAcceptEncoding = autoFillAcceptEncoding;
        return this;
    }

    /**
     * Strictly validates {@link HttpHeaderNames#CONTENT_ENCODING}. If an unsupported
     * {@link HttpHeaderNames#CONTENT_ENCODING} is received, the {@link HttpResponse} will be failed with
     * {@link UnsupportedEncodingException}.
     *
     * <p>This option is disabled by default. That means if an unsupported
     * {@link HttpHeaderNames#CONTENT_ENCODING} is received, the decoding for the content will be skipped.
     */
    public DecodingClientBuilder strictContentEncoding(boolean strict) {
        strictContentEncoding = strict;
        return this;
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link DecodingClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, DecodingClient> newDecorator() {
        return this::build;
    }

    /**
     * Returns a newly-created {@link DecodingClient} based on the properties of this builder.
     */
    public DecodingClient build(HttpClient delegate) {
        return new DecodingClient(delegate, decoderFactories, autoFillAcceptEncoding, strictContentEncoding);
    }
}
