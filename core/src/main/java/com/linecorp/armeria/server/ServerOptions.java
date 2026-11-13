/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.util.AbstractOptions;
import com.linecorp.armeria.server.encoding.Compression;
import com.linecorp.armeria.server.logging.ContentPreview;

/**
 * HTTP content processing options for a server, virtual host or service.
 * Unspecified options are inherited from the enclosing scope. All content processing is disabled by default.
 */
@UnstableApi
public final class ServerOptions extends AbstractOptions<ServerOption<Object>, ServerOptionValue<Object>> {

    /**
     * Previews request content after decompression and response content before compression.
     * Use {@link ContentPreview#disabled()} to disable an inherited preview configuration.
     */
    public static final ServerOption<ContentPreview> CONTENT_PREVIEW =
            ServerOption.define("CONTENT_PREVIEW", ContentPreview.disabled());

    /**
     * Compresses responses, including error responses, when the client accepts an encoding.
     * Use {@link Compression#disabled()} to disable inherited compression.
     */
    public static final ServerOption<Compression> COMPRESSION =
            ServerOption.define("COMPRESSION", Compression.disabled());

    /**
     * The decoders for request content. An empty list disables inherited decompression.
     * Unsupported encodings are passed through. The decoded content is subject to the request size limit.
     * A new value replaces the entire list of decoders.
     */
    public static final ServerOption<List<StreamDecoderFactory>> DECOMPRESSION =
            ServerOption.define("DECOMPRESSION", ImmutableList.of(), factories -> {
                final List<StreamDecoderFactory> copy = ImmutableList.copyOf(factories);
                final ImmutableSet.Builder<String> encodings = ImmutableSet.builder();
                for (StreamDecoderFactory factory : copy) {
                    encodings.add(Ascii.toLowerCase(factory.encodingHeaderValue()));
                }
                if (encodings.build().size() != copy.size()) {
                    throw new IllegalArgumentException("duplicate content encoding in decoder factories");
                }
                return copy;
            });

    private static final ServerOptions EMPTY = new ServerOptions(ImmutableList.of());

    /**
     * Returns an empty set of explicitly configured options.
     */
    public static ServerOptions of() {
        return EMPTY;
    }

    /**
     * Returns the specified options. Later values replace earlier values of the same option.
     */
    public static ServerOptions of(ServerOptionValue<?>... values) {
        return of(Arrays.asList(requireNonNull(values, "values")));
    }

    /**
     * Returns the specified options.
     */
    public static ServerOptions of(Iterable<? extends ServerOptionValue<?>> values) {
        requireNonNull(values, "values");
        return values instanceof ServerOptions ? (ServerOptions) values : new ServerOptions(values);
    }

    /**
     * Merges the specified options, giving the additional values precedence.
     */
    public static ServerOptions of(ServerOptions baseOptions, ServerOptionValue<?>... additionalValues) {
        return of(baseOptions, Arrays.asList(requireNonNull(additionalValues, "additionalValues")));
    }

    /**
     * Merges the specified options, giving the additional values precedence.
     */
    public static ServerOptions of(ServerOptions baseOptions,
                                   Iterable<? extends ServerOptionValue<?>> additionalValues) {
        return new ServerOptions(baseOptions, additionalValues);
    }

    private ServerOptions(Iterable<? extends ServerOptionValue<?>> values) {
        super(values);
    }

    private ServerOptions(ServerOptions baseOptions,
                          Iterable<? extends ServerOptionValue<?>> additionalValues) {
        super(baseOptions, additionalValues);
    }
}
