/*
 * Copyright 2015 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutPolicy;
import com.linecorp.armeria.common.util.AbstractOption;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.ConstantPool;

/**
 * A client option.
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
     * The {@link TimeoutPolicy} for a socket write
     */
    public static final ClientOption<TimeoutPolicy> WRITE_TIMEOUT_POLICY = valueOf("WRITE_TIMEOUT_POLICY");

    /**
     * The {@link TimeoutPolicy} for a server reply to a client call.
     */
    public static final ClientOption<TimeoutPolicy> RESPONSE_TIMEOUT_POLICY = valueOf(
            "RESPONSE_TIMEOUT_POLICY");

    /**
     * The additional HTTP headers to send with requests. Used only when the underlying
     * {@link SessionProtocol} is HTTP.
     */
    public static final ClientOption<HttpHeaders> HTTP_HEADERS = valueOf("HTTP_HEADERS");

    /**
     * The {@link Function} that decorates the client components provided by {@link Client}.
     */
    public static final ClientOption<Function<Client, Client>> DECORATOR = valueOf(
            "DECORATOR");

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
