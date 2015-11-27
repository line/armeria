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
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.apache.thrift.protocol.TProtocolFactory;

import com.linecorp.armeria.client.http.SimpleHttpClientCodec;
import com.linecorp.armeria.client.thrift.ThriftClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

import io.netty.util.concurrent.Future;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override.
 */
public final class ClientBuilder {

    private final URI uri;
    private final List<ClientOptionValue<?>> optionsList = new ArrayList<>();

    private RemoteInvokerFactory remoteInvokerFactory = RemoteInvokerFactory.DEFAULT;

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@code uri}.
     */
    public ClientBuilder(String uri) {
        this(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Creates a new {@link ClientBuilder} that builds the client that connects to the specified {@link URI}.
     */
    public ClientBuilder(URI uri) {
        this.uri = requireNonNull(uri, "uri");
    }

    /**
     * Sets the {@link RemoteInvokerFactory} of the client. The default is {@link RemoteInvokerFactory#DEFAULT}.
     */
    public ClientBuilder remoteInvokerFactory(RemoteInvokerFactory remoteInvokerFactory) {
        this.remoteInvokerFactory = requireNonNull(remoteInvokerFactory, "remoteInvokerFactory");
        return this;
    }

    /**
     * Adds the specified {@link ClientOptions}.
     */
    public ClientBuilder options(ClientOptions options) {
        requireNonNull(options, "options");
        optionsList.addAll(options.asMap().values());
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public ClientBuilder options(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        optionsList.addAll(Arrays.asList(options));
        return this;
    }

    /**
     * Add the specified {@link ClientOption} and its {@code value}.
     */
    public <T> ClientBuilder option(ClientOption<T> option, T value) {
        requireNonNull(option, "option");
        optionsList.add(option.newValue(value));
        return this;
    }

    /**
     * Creates a new client which implements the specified {@code interfaceClass}.
     */
    @SuppressWarnings("unchecked")
    public <T> T build(Class<T> interfaceClass) {
        requireNonNull(interfaceClass, "interfaceClass");
        final ClientOptions options = ClientOptions.of(optionsList.toArray(
                new ClientOptionValue<?>[optionsList.size()]));

        final Scheme scheme = Scheme.parse(uri.getScheme());
        final SessionProtocol sessionProtocol = scheme.sessionProtocol();
        final RemoteInvoker remoteInvoker = options.remoteInvokerDecorator()
                                                   .apply(remoteInvokerFactory.getInvoker(sessionProtocol));
        if (remoteInvoker == null) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }

        final ClientCodec codec = options.clientCodecDecorator()
                                         .apply(createCodec(uri, scheme, interfaceClass));

        final InvocationHandler defaultHandler = (proxy, method, args) -> {
            try {
                Future<Object> resultFuture = remoteInvoker.invoke(uri, options, codec, method, args);
                if (codec.isAsyncClient()) {
                    return method.getReturnType().isInstance(resultFuture) ? resultFuture : null;
                } else {
                    return resultFuture.sync().getNow();
                }
            } catch (Throwable cause) {
                final Throwable finalCause;
                if (cause instanceof ClosedChannelException) {
                    finalCause = ClosedSessionException.INSTANCE;
                } else if (cause instanceof Error ||
                           cause instanceof RuntimeException ||
                           Stream.of(method.getExceptionTypes()).anyMatch(v -> v.isInstance(cause))) {
                    finalCause = cause;
                } else {
                    finalCause = new UndeclaredThrowableException(cause);
                }

                throw finalCause;
            }
        };
        final InvocationHandler handler = options.invocationHandlerDecorator().apply(defaultHandler);
        ClassLoader classLoader = interfaceClass.getClassLoader();
        return (T) Proxy.newProxyInstance(classLoader, new Class[] { interfaceClass }, handler);
    }

    private static ClientCodec createCodec(URI uri, Scheme scheme, Class<?> interfaceClass) {
        SessionProtocol sessionProtocol = scheme.sessionProtocol();
        SerializationFormat serializationFormat = scheme.serializationFormat();
        if (SerializationFormat.ofThrift().contains(serializationFormat)) {
            TProtocolFactory protocolFactory = ThriftProtocolFactories.get(serializationFormat);
            return new ThriftClientCodec(uri, interfaceClass, protocolFactory);

        }

        if (SessionProtocol.ofHttp().contains(sessionProtocol) &&
                   serializationFormat == SerializationFormat.NONE) {
            return new SimpleHttpClientCodec(uri.getHost());
        }

        throw new IllegalArgumentException("unsupported scheme:" + scheme);
    }
}
