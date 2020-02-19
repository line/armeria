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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AbstractOption;

import io.netty.util.ConstantPool;

/**
 * A client option.
 *
 * @param <T> the type of the option value
 */
public final class ClientOption<T> extends AbstractOption<T> {

    @SuppressWarnings("rawtypes")
    private static final ConstantPool pool = new ConstantPool() {
        @Override
        protected ClientOption<Object> newConstant(int id, String name) {
            return new ClientOption<>(id, name);
        }
    };

    /**
     * The {@link ClientFactory} used for creating a client.
     */
    public static final ClientOption<ClientFactory> FACTORY = valueOf("FACTORY");

    /**
     * The timeout of a socket write.
     */
    public static final ClientOption<Long> WRITE_TIMEOUT_MILLIS = valueOf("WRITE_TIMEOUT_MILLIS");

    /**
     * The timeout of a socket write.
     *
     * @deprecated Use {@link #WRITE_TIMEOUT_MILLIS}.
     */
    @Deprecated
    public static final ClientOption<Long> DEFAULT_WRITE_TIMEOUT_MILLIS = WRITE_TIMEOUT_MILLIS;

    /**
     * The timeout of a server reply to a client call.
     */
    public static final ClientOption<Long> RESPONSE_TIMEOUT_MILLIS = valueOf("RESPONSE_TIMEOUT_MILLIS");

    /**
     * The timeout of a server reply to a client call.
     *
     * @deprecated Use {@link #RESPONSE_TIMEOUT_MILLIS}.
     */
    @Deprecated
    public static final ClientOption<Long> DEFAULT_RESPONSE_TIMEOUT_MILLIS = RESPONSE_TIMEOUT_MILLIS;

    /**
     * The maximum allowed length of a server response.
     */
    public static final ClientOption<Long> MAX_RESPONSE_LENGTH = valueOf("DEFAULT_MAX_RESPONSE_LENGTH");

    /**
     * The maximum allowed length of a server response.
     *
     * @deprecated Use {@link #MAX_RESPONSE_LENGTH};
     */
    @Deprecated
    public static final ClientOption<Long> DEFAULT_MAX_RESPONSE_LENGTH = MAX_RESPONSE_LENGTH;

    /**
     * The additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public static final ClientOption<HttpHeaders> HTTP_HEADERS = valueOf("HTTP_HEADERS");

    /**
     * The {@link Function} that decorates the client components.
     */
    public static final ClientOption<ClientDecoration> DECORATION = valueOf("DECORATION");

    /**
     * The {@link Supplier} that generates a {@link RequestId}.
     */
    public static final ClientOption<Supplier<RequestId>> REQUEST_ID_GENERATOR = valueOf(
            "REQUEST_ID_GENERATOR");

    /**
     * A {@link Function} that remaps a target {@link Endpoint} into an {@link EndpointGroup}.
     *
     * @see ClientBuilder#endpointRemapper(Function)
     */
    public static final ClientOption<Function<? super Endpoint, ? extends EndpointGroup>> ENDPOINT_REMAPPER =
            valueOf("ENDPOINT_REMAPPER");

    /**
     * Returns the {@link ClientOption} of the specified name.
     */
    @SuppressWarnings("unchecked")
    public static <T> ClientOption<T> valueOf(String name) {
        return (ClientOption<T>) pool.valueOf(name);
    }

    /**
     * Creates a new {@link ClientOption} of the specified unique {@code name}.
     */
    private ClientOption(int id, String name) {
        super(id, name);
    }

    /**
     * Creates a new value of this option.
     */
    public ClientOptionValue<T> newValue(T value) {
        requireNonNull(value, "value");
        return new ClientOptionValue<>(this, value);
    }
}
