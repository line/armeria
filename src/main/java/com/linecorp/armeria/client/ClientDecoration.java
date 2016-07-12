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

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

public final class ClientDecoration {

    public static final ClientDecoration NONE = new ClientDecoration(Collections.emptyList());

    public static <T extends Client<? super I, ? extends O>, R extends Client<I, O>,
                   I extends Request, O extends Response>
    ClientDecoration of(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {
        return new ClientDecorationBuilder().add(requestType, responseType, decorator).build();
    }

    private final List<Entry<?, ?>> entries;

    ClientDecoration(List<Entry<?, ?>> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public <I extends Request, O extends Response> Client<I, O> decorate(
            Class<I> requestType, Class<O> responseType, Client<I, O> client) {

        for (Entry<?, ?> e : entries) {
            if (!requestType.isAssignableFrom(e.requestType()) ||
                !responseType.isAssignableFrom(e.responseType())) {
                continue;
            }

            @SuppressWarnings("unchecked")
            final Function<Client<? super I, ? extends O>, Client<I, O>> decorator = ((Entry<I, O>) e).decorator();
            client = decorator.apply(client);
        }

        return client;
    }

    static final class Entry<I extends Request, O extends Response> {
        private final Class<I> requestType;
        private final Class<O> responseType;
        private final Function<Client<? super I, ? extends O>, Client<I, O>> decorator;

        Entry(Class<I> requestType, Class<O> responseType,
              Function<? extends Client<? super I, ? extends O>, ? extends Client<I, O>> decorator) {
            this.requestType = requestType;
            this.responseType = responseType;

            @SuppressWarnings("unchecked")
            Function<Client<? super I, ? extends O>, Client<I, O>> castDecorator =
                    (Function<Client<? super I, ? extends O>, Client<I, O>>) decorator;
            this.decorator = castDecorator;
        }

        public Class<I> requestType() {
            return requestType;
        }

        public Class<O> responseType() {
            return responseType;
        }

        public Function<Client<? super I, ? extends O>, Client<I, O>> decorator() {
            return decorator;
        }

        @Override
        public String toString() {
            return '(' +
                   requestType.getSimpleName() + ", " +
                   responseType.getSimpleName() + ", " +
                   decorator +
                   ')';
        }
    }
}
