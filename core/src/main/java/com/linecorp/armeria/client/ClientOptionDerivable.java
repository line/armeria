/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.function.Function;

/**
 * A client that allows creating a new client with alternative {@link ClientOption}s.
 *
 * @param <T> self type
 *
 * @see ClientOptionsBuilder ClientOptionsBuilder, for more information about how the base options and
 *                           additional options are merged when a derived client is created.
 */
@FunctionalInterface
public interface ClientOptionDerivable<T> {

    /**
     * Creates a new derived client that connects to the same {@link URI} with this client and
     * the specified {@code additionalOptions}.
     */
    default T withOptions(ClientOptionValue<?>... additionalOptions) {
        requireNonNull(additionalOptions, "additionalOptions");
        return derive(options -> new ClientOptionsBuilder(options).options(additionalOptions).build());
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with this client and
     * the specified {@code additionalOptions}.
     */
    default T withOptions(Iterable<ClientOptionValue<?>> additionalOptions) {
        requireNonNull(additionalOptions, "additionalOptions");
        return derive(options -> new ClientOptionsBuilder(options).options(additionalOptions).build());
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with this client but with different
     * {@link ClientOption}s. For example:
     *
     * <pre>{@code
     * HttpClient derivedHttpClient = httpClient.derive(options -> {
     *     ClientOptionsBuilder builder = new ClientOptionsBuilder(options);
     *     builder.decorator(...);   // Add a decorator.
     *     builder.httpHeader(...); // Add an HTTP header.
     *     return builder.build();
     * });
     * }</pre>
     *
     * @param configurator a {@link Function} whose input is the original {@link ClientOptions} of the client
     *                     being derived from and whose output is the {@link ClientOptions} of the new derived
     *                     client
     */
    T derive(Function<? super ClientOptions, ClientOptions> configurator);
}
