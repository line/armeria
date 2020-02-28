/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.ClientOption.DECORATION;
import static com.linecorp.armeria.client.ClientOption.ENDPOINT_REMAPPER;
import static com.linecorp.armeria.client.ClientOption.FACTORY;
import static com.linecorp.armeria.client.ClientOption.HTTP_HEADERS;
import static com.linecorp.armeria.client.ClientOption.MAX_RESPONSE_LENGTH;
import static com.linecorp.armeria.client.ClientOption.REQUEST_ID_GENERATOR;
import static com.linecorp.armeria.client.ClientOption.RESPONSE_TIMEOUT_MILLIS;
import static com.linecorp.armeria.client.ClientOption.WRITE_TIMEOUT_MILLIS;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AbstractOptions;

/**
 * A set of {@link ClientOption}s and their respective values.
 */
public final class ClientOptions
        extends AbstractOptions<ClientOption<Object>, ClientOptionValue<Object>> {

    private static final ClientOptions EMPTY = new ClientOptions(ImmutableList.of());

    /**
     * Returns an empty singleton {@link ClientOptions}.
     */
    public static ClientOptions of() {
        return EMPTY;
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(ClientOptionValue<?>... values) {
        requireNonNull(values, "values");
        if (values.length == 0) {
            return EMPTY;
        }
        return of(Arrays.asList(values));
    }

    /**
     * Returns the {@link ClientOptions} with the specified {@link ClientOptionValue}s.
     */
    public static ClientOptions of(Iterable<? extends ClientOptionValue<?>> values) {
        requireNonNull(values, "values");
        if (values instanceof ClientOptions) {
            return (ClientOptions) values;
        }
        return new ClientOptions(values);
    }

    /**
     * Merges the specified {@link ClientOptions} and {@link ClientOptionValue}s.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions, ClientOptionValue<?>... additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");
        if (additionalValues.length == 0) {
            return baseOptions;
        }
        return new ClientOptions(baseOptions, Arrays.asList(additionalValues));
    }

    /**
     * Merges the specified {@link ClientOptions} and {@link ClientOptionValue}s.
     *
     * @return the merged {@link ClientOptions}
     */
    public static ClientOptions of(ClientOptions baseOptions,
                                   Iterable<? extends ClientOptionValue<?>> additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");
        return new ClientOptions(baseOptions, additionalValues);
    }

    /**
     * Returns a newly created {@link ClientOptionsBuilder}.
     */
    public static ClientOptionsBuilder builder() {
        return new ClientOptionsBuilder();
    }

    private ClientOptions(Iterable<? extends ClientOptionValue<?>> values) {
        super(values);
    }

    private ClientOptions(ClientOptions baseOptions,
                          Iterable<? extends ClientOptionValue<?>> additionalValues) {
        super(baseOptions, additionalValues);
    }

    /**
     * Returns the {@link ClientFactory} used for creating a client.
     */
    public ClientFactory factory() {
        return get(FACTORY);
    }

    /**
     * Returns the timeout of a socket write.
     */
    public long writeTimeoutMillis() {
        return get(WRITE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the timeout of a server reply to a client call.
     */
    public long responseTimeoutMillis() {
        return get(RESPONSE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the maximum allowed length of a server response.
     */
    public long maxResponseLength() {
        return get(MAX_RESPONSE_LENGTH);
    }

    /**
     * Returns the {@link Function}s that decorate the components of a client.
     */
    public ClientDecoration decoration() {
        return get(DECORATION);
    }

    /**
     * Returns the additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public HttpHeaders httpHeaders() {
        return get(HTTP_HEADERS);
    }

    /**
     * Returns the {@link Supplier} that generates a {@link RequestId}.
     */
    public Supplier<RequestId> requestIdGenerator() {
        return get(REQUEST_ID_GENERATOR);
    }

    /**
     * Returns the {@link Function} that remaps a target {@link Endpoint} into an {@link EndpointGroup}.
     *
     * @see ClientBuilder#endpointRemapper(Function)
     */
    public Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper() {
        return get(ENDPOINT_REMAPPER);
    }

    /**
     * Returns a new {@link ClientOptionsBuilder} created from this {@link ClientOptions}.
     */
    public ClientOptionsBuilder toBuilder() {
        return new ClientOptionsBuilder(this);
    }
}
