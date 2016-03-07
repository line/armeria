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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.function.Function;

/**
 * Creates a new client that connects to a specified {@link URI}.
 */
public final class Clients {

    /**
     * Creates a new client that connects to the specified {@code uri} using the default
     * {@link RemoteInvokerFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    public static <T> T newClient(String uri, Class<T> interfaceClass, ClientOptionValue<?>... options) {
        return newClient(RemoteInvokerFactory.DEFAULT, uri, interfaceClass, options);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using the default
     * {@link RemoteInvokerFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptions}
     */
    public static <T> T newClient(String uri, Class<T> interfaceClass, ClientOptions options) {
        return newClient(RemoteInvokerFactory.DEFAULT, uri, interfaceClass, options);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using an alternative
     * {@link RemoteInvokerFactory}.
     *
     * @param remoteInvokerFactory an alternative {@link RemoteInvokerFactory}
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    public static <T> T newClient(RemoteInvokerFactory remoteInvokerFactory, String uri,
                                  Class<T> interfaceClass, ClientOptionValue<?>... options) {

        return new ClientBuilder(uri).remoteInvokerFactory(remoteInvokerFactory).options(options)
                                     .build(interfaceClass);
    }

    /**
     * Creates a new client that connects to the specified {@code uri} using an alternative
     * {@link RemoteInvokerFactory}.
     *
     * @param remoteInvokerFactory an alternative {@link RemoteInvokerFactory}
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptions}
     */
    public static <T> T newClient(RemoteInvokerFactory remoteInvokerFactory, String uri,
                                  Class<T> interfaceClass, ClientOptions options) {
        return new ClientBuilder(uri).remoteInvokerFactory(remoteInvokerFactory).options(options)
                                     .build(interfaceClass);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link RemoteInvokerFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    public static <T> T newClient(URI uri, Class<T> interfaceClass, ClientOptionValue<?>... options) {
        return newClient(RemoteInvokerFactory.DEFAULT, uri, interfaceClass, options);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using the default
     * {@link RemoteInvokerFactory}.
     *
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptions}
     */
    public static <T> T newClient(URI uri, Class<T> interfaceClass, ClientOptions options) {
        return newClient(RemoteInvokerFactory.DEFAULT, uri, interfaceClass, options);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using an alternative
     * {@link RemoteInvokerFactory}.
     *
     * @param remoteInvokerFactory an alternative {@link RemoteInvokerFactory}
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptionValue}s
     */
    public static <T> T newClient(RemoteInvokerFactory remoteInvokerFactory, URI uri, Class<T> interfaceClass,
                                  ClientOptionValue<?>... options) {
        return new ClientBuilder(uri).remoteInvokerFactory(remoteInvokerFactory).options(options)
                                     .build(interfaceClass);
    }

    /**
     * Creates a new client that connects to the specified {@link URI} using an alternative
     * {@link RemoteInvokerFactory}.
     *
     * @param remoteInvokerFactory an alternative {@link RemoteInvokerFactory}
     * @param uri the URI of the server endpoint
     * @param interfaceClass the type of the new client
     * @param options the {@link ClientOptions}
     */
    public static <T> T newClient(RemoteInvokerFactory remoteInvokerFactory, URI uri, Class<T> interfaceClass,
                                  ClientOptions options) {
        return new ClientBuilder(uri).remoteInvokerFactory(remoteInvokerFactory).options(options)
                                     .build(interfaceClass);
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * with the specified {@code additionalOptions}. Note that the derived client will use the options of
     * the specified {@code client} unless specified in {@code additionalOptions}.
     */
    public static <T> T newDerivedClient(T client, ClientOptionValue<?>... additionalOptions) {
        requireNonNull(additionalOptions, "additionalOptions");
        // TODO(trustin): Reduce the cost of creating a derived ClientOptions.
        return newDerivedClient(client, baseOptions -> ClientOptions.of(baseOptions, additionalOptions));
    }

    /**
     * Creates a new derived client that connects to the same {@link URI} with the specified {@code client}
     * with the specified {@code additionalOptions}. Note that the derived client will use the options of
     * the specified {@code client} unless specified in {@code additionalOptions}.
     */
    public static <T> T newDerivedClient(T client, Iterable<ClientOptionValue<?>> additionalOptions) {
        requireNonNull(additionalOptions, "additionalOptions");
        // TODO(trustin): Reduce the cost of creating a derived ClientOptions.
        return newDerivedClient(client, baseOptions -> ClientOptions.of(baseOptions, additionalOptions));
    }

    private static <T> T newDerivedClient(T client,
                                          Function<ClientOptions, ClientOptions> optionFactory) {

        requireNonNull(client, "client");

        ClientInvocationHandler parent = null;
        try {
            InvocationHandler ih = Proxy.getInvocationHandler(client);
            if (ih instanceof ClientInvocationHandler) {
                parent = (ClientInvocationHandler) ih;
            }
        } catch (IllegalArgumentException expected) {
            // Will reach here when 'client' is not a proxy object.
        }

        if (parent == null) {
            throw new IllegalArgumentException("not a client: " + client);
        }

        final Class<?> interfaceClass = parent.interfaceClass();

        @SuppressWarnings("unchecked")
        final T derived = (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class[] { interfaceClass },
                new ClientInvocationHandler(parent.eventLoopGroup(),
                                            parent.uri(), interfaceClass, parent.client(),
                                            optionFactory.apply(parent.options())));

        return derived;
    }

    private Clients() {}
}


