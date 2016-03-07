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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.apache.thrift.protocol.TProtocolFactory;

import com.linecorp.armeria.client.http.SimpleHttpClientCodec;
import com.linecorp.armeria.client.thrift.ThriftClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutPolicy;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

/**
 * Creates a new client that connects to the specified {@link URI} using the builder pattern. Use the factory
 * methods in {@link Clients} if you do not have many options to override.
 */
public final class ClientBuilder {

    private final URI uri;
    private final Map<ClientOption<?>, ClientOptionValue<?>> options = new LinkedHashMap<>();

    private RemoteInvokerFactory remoteInvokerFactory = RemoteInvokerFactory.DEFAULT;
    private Function<Client, Client> decorator;

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

        final Map<ClientOption<Object>, ClientOptionValue<Object>> optionMap = options.asMap();
        for (ClientOptionValue<?> o : optionMap.values()) {
            validateOption(o.option());
        }

        this.options.putAll(optionMap);
        return this;
    }

    /**
     * Adds the specified {@link ClientOptionValue}s.
     */
    public ClientBuilder options(ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        for (int i = 0; i < options.length; i++) {
            final ClientOptionValue<?> o = options[i];
            if (o == null) {
                throw new NullPointerException("options[" + i + ']');
            }

            if (o.option() == ClientOption.DECORATOR && decorator != null) {
                throw new IllegalArgumentException(
                        "options[" + i + "]: option(" + ClientOption.DECORATOR +
                        ") and decorator() are mutually exclusive.");
            }

            this.options.put(o.option(), o);
        }
        return this;
    }

    /**
     * Adds the specified {@link ClientOption} and its {@code value}.
     */
    public <T> ClientBuilder option(ClientOption<T> option, T value) {
        validateOption(option);
        options.put(option, option.newValue(value));
        return this;
    }

    private void validateOption(ClientOption<?> option) {
        requireNonNull(option, "option");
        if (option == ClientOption.DECORATOR && decorator != null) {
            throw new IllegalArgumentException(
                    "option(" + ClientOption.DECORATOR + ") and decorator() are mutually exclusive.");
        }
    }

    /**
     * Sets the timeout of a socket write attempt in milliseconds.
     *
     * @param writeTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return writeTimeout(Duration.ofMillis(writeTimeoutMillis));
    }

    /**
     * Sets the timeout of a socket write attempt.
     *
     * @param writeTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientBuilder writeTimeout(Duration writeTimeout) {
        return writeTimeout(TimeoutPolicy.ofFixed(requireNonNull(writeTimeout, "writeTimeout")));
    }

    /**
     * Sets the {@link TimeoutPolicy} of a socket write attempt.
     */
    public ClientBuilder writeTimeout(TimeoutPolicy writeTimeoutPolicy) {
        return option(ClientOption.WRITE_TIMEOUT_POLICY,
                      requireNonNull(writeTimeoutPolicy, "writeTimeoutPolicy"));
    }

    /**
     * Sets the timeout of a response in milliseconds.
     *
     * @param responseTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public ClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return responseTimeout(Duration.ofMillis(responseTimeoutMillis));
    }

    /**
     * Sets the timeout of a response.
     *
     * @param responseTimeout the timeout. {@code 0} disables the timeout.
     */
    public ClientBuilder responseTimeout(Duration responseTimeout) {
        return responseTimeout(TimeoutPolicy.ofFixed(requireNonNull(responseTimeout, "responseTimeout")));
    }

    /**
     * Sets the {@link TimeoutPolicy} of a response.
     */
    public ClientBuilder responseTimeout(TimeoutPolicy responseTimeoutPolicy) {
        return option(ClientOption.RESPONSE_TIMEOUT_POLICY,
                      requireNonNull(responseTimeoutPolicy, "responseTimeoutPolicy"));
    }

    /**
     * Adds the specified {@code decorator}.
     */
    public ClientBuilder decorator(Function<Client, Client> decorator) {
        requireNonNull(decorator, "decorator");

        if (options.containsKey(ClientOption.DECORATOR)) {
            throw new IllegalArgumentException(
                    "decorator() and option(" + ClientOption.DECORATOR + ") are mutually exclusive.");
        }

        if (this.decorator == null) {
            this.decorator = decorator;
        } else {
            this.decorator = this.decorator.andThen(decorator);
        }

        return this;
    }

    /**
     * Adds the specified {@code invokerDecorator} that decorates a {@link RemoteInvoker}.
     */
    public ClientBuilder invokerDecorator(
            Function<? extends RemoteInvoker, ? extends RemoteInvoker> invokerDecorator) {
        requireNonNull(invokerDecorator, "invokerDecorator");

        @SuppressWarnings("unchecked")
        final Function<RemoteInvoker, RemoteInvoker> castInvokerDecorator =
                (Function<RemoteInvoker, RemoteInvoker>) invokerDecorator;

        return decorator(d -> d.decorateInvoker(castInvokerDecorator));
    }

    /**
     * Adds the specified {@code codecDecorator} that decorates an {@link ClientCodec}.
     */
    public ClientBuilder codecDecorator(
            Function<? extends ClientCodec, ? extends ClientCodec> codecDecorator) {
        requireNonNull(codecDecorator, "codecDecorator");

        @SuppressWarnings("unchecked")
        final Function<ClientCodec, ClientCodec> castCodecDecorator =
                (Function<ClientCodec, ClientCodec>) codecDecorator;

        return decorator(d -> d.decorateCodec(castCodecDecorator));
    }

    /**
     * Creates a new client which implements the specified {@code interfaceClass}.
     */
    @SuppressWarnings("unchecked")
    public <T> T build(Class<T> interfaceClass) {
        requireNonNull(interfaceClass, "interfaceClass");

        if (decorator != null) {
            options.put(ClientOption.DECORATOR, ClientOption.DECORATOR.newValue(decorator));
        }

        final ClientOptions options = ClientOptions.of(this.options.values());

        final Client client = options.decorator().apply(newClient(interfaceClass));

        final InvocationHandler handler = new ClientInvocationHandler(
                remoteInvokerFactory.eventLoopGroup(), uri, interfaceClass, client, options);

        return (T) Proxy.newProxyInstance(interfaceClass.getClassLoader(),
                                          new Class[] { interfaceClass },
                                          handler);
    }

    private Client newClient(Class<?> interfaceClass) {
        final Scheme scheme = Scheme.parse(uri.getScheme());
        final SessionProtocol sessionProtocol = scheme.sessionProtocol();

        final RemoteInvoker remoteInvoker = remoteInvokerFactory.getInvoker(sessionProtocol);
        if (remoteInvoker == null) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }

        final ClientCodec codec = createCodec(uri, scheme, interfaceClass);

        return new SimpleClient(codec, remoteInvoker);
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
