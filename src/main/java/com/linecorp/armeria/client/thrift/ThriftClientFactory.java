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

package com.linecorp.armeria.client.thrift;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionDerivable;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;

public class ThriftClientFactory extends DecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES;

    static {
        final ImmutableSet.Builder<Scheme> builder = ImmutableSet.builder();
        for (SessionProtocol p : SessionProtocol.ofHttp()) {
            for (SerializationFormat f : SerializationFormat.ofThrift()) {
                builder.add(Scheme.of(f, p));
            }
        }
        SUPPORTED_SCHEMES = builder.build();
    }

    public ThriftClientFactory(ClientFactory httpClientFactory) {
        super(validate(httpClientFactory));
    }

    private static ClientFactory validate(ClientFactory httpClientFactory) {
        requireNonNull(httpClientFactory, "httpClientFactory");

        for (SessionProtocol p : SessionProtocol.ofHttp()) {
            if (!httpClientFactory.supportedSchemes().contains(Scheme.of(SerializationFormat.NONE, p))) {
                throw new IllegalArgumentException(p.uriText() + " not supported by: " + httpClientFactory);
            }
        }

        return httpClientFactory;
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validate(uri, clientType, options);
        final SerializationFormat serializationFormat = scheme.serializationFormat();

        final Client<ThriftCall, ThriftReply> delegate = options.decoration().decorate(
                ThriftCall.class, ThriftReply.class,
                new ThriftClientDelegate(newHttpClient(uri, scheme, options),
                                         uri.getPath(), serializationFormat));

        final ThriftClient thriftClient = new DefaultThriftClient(
                delegate, eventLoopSupplier(), scheme.sessionProtocol(), options, newEndpoint(uri));

        if (clientType == ThriftClient.class) {
            @SuppressWarnings("unchecked")
            final T client = (T) thriftClient;
            return client;
        } else {
            @SuppressWarnings("unchecked")
            T client = (T) Proxy.newProxyInstance(
                    clientType.getClassLoader(),
                    new Class<?>[] { clientType, ClientOptionDerivable.class },
                    new ThriftClientInvocationHandler(thriftClient, uri.getPath(), clientType));
            return client;
        }
    }

    private Client<HttpRequest, HttpResponse> newHttpClient(URI uri, Scheme scheme, ClientOptions options) {
        try {
            @SuppressWarnings("unchecked")
            Client<HttpRequest, HttpResponse> client = delegate().newClient(
                    new URI(Scheme.of(SerializationFormat.NONE, scheme.sessionProtocol()).uriText(),
                            uri.getAuthority(), null, null, null),
                    Client.class, options);
            return client;
        } catch (URISyntaxException e) {
            throw new Error(e); // Should never happen.
        }
    }
}
