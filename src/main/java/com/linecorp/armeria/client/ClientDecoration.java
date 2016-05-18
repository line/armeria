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
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientDecoration.Entry;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

public final class ClientDecoration implements Iterable<Entry> {

    public static final ClientDecoration NONE = new ClientDecoration(Collections.emptyList());

    public static ClientDecoration of(
            Class<? extends Request> requestType, Class<? extends Response> responseType,
            Function<? extends Client, ? extends Client> decorator) {
        return new ClientDecorationBuilder().add(requestType, responseType, decorator).build();
    }

    private final List<Entry> entries;

    ClientDecoration(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    @Override
    public Iterator<Entry> iterator() {
        return entries.iterator();
    }

    public Client decorate(Class<? extends Request> requestType,
                           Class<? extends Response> responseType, Client client) {

        for (Entry e : this) {
            if (!requestType.isAssignableFrom(e.requestType()) ||
                !responseType.isAssignableFrom(e.responseType())) {
                continue;
            }

            final Function<Client, Client> decorator = e.decorator();
            client = decorator.apply(client);
        }

        return client;
    }

    public static final class Entry {
        private final Class<? extends Request> requestType;
        private final Class<? extends Response> responseType;
        private final Function<? extends Client, ? extends Client> decorator;

        Entry(Class<? extends Request> requestType, Class<? extends Response> responseType,
              Function<? extends Client, ? extends Client> decorator) {
            this.requestType = requestType;
            this.responseType = responseType;
            this.decorator = decorator;
        }

        @SuppressWarnings("unchecked")
        public <I> Class<I> requestType() {
            return (Class<I>) requestType;
        }

        @SuppressWarnings("unchecked")
        public <O> Class<O> responseType() {
            return (Class<O>) responseType;
        }

        @SuppressWarnings("unchecked")
        public Function<Client, Client> decorator() {
            return (Function<Client, Client>) decorator;
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
