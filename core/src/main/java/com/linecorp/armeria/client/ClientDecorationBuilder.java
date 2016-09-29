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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientDecoration.Entry;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Creates a new {@link ClientDecoration} using the builder pattern.
 */
public final class ClientDecorationBuilder {

    private final List<Entry<?, ?>> entries = new ArrayList<>();

    /**
     * Adds a new decorator {@link Function}.
     *
     * @param requestType the type of the {@link Request} that the {@code decorator} is interested in
     * @param responseType the type of the {@link Response} that the {@code decorator} is interested in
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     * @param <T> the type of the {@link Client} being decorated
     * @param <R> the type of the {@link Client} produced by the {@code decorator}
     */
    public <T extends Client<? super I, ? extends O>, R extends Client<I, O>,
            I extends Request, O extends Response>
    ClientDecorationBuilder add(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {

        requireNonNull(requestType, "requestType");
        requireNonNull(responseType, "responseType");
        requireNonNull(decorator, "decorator");

        entries.add(new Entry<>(requestType, responseType, decorator));
        return this;
    }

    /**
     * Creates a new {@link ClientDecoration} with the decorators added so far via
     * {@link #add(Class, Class, Function)}.
     */
    public ClientDecoration build() {
        return new ClientDecoration(entries);
    }
}
