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

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.http.HttpClientFactory;
import com.linecorp.armeria.client.thrift.THttpClientFactory;
import com.linecorp.armeria.common.Scheme;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link ClientFactory} which combines all known official {@link ClientFactory} implementations.
 * It currently combines the following {@link ClientFactory}s:
 * <ul>
 *   <li>{@link HttpClientFactory}</li>
 *   <li>{@link THttpClientFactory}</li>
 * </ul>
 */
public class AllInOneClientFactory extends AbstractClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(AllInOneClientFactory.class);

    static {
        if (AllInOneClientFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            Runtime.getRuntime().addShutdownHook(new Thread(ClientFactory::closeDefault));
        }
    }

    private final ClientFactory mainFactory;
    private final Map<Scheme, ClientFactory> clientFactories;

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     */
    public AllInOneClientFactory() {
        this(SessionOptions.DEFAULT);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     */
    public AllInOneClientFactory(SessionOptions options) {
        // TODO(trustin): Allow specifying different options for different session protocols.
        //                We have only one session protocol at the moment, so this is OK so far.
        final HttpClientFactory httpClientFactory = new HttpClientFactory(options);
        final THttpClientFactory thriftClientFactory = new THttpClientFactory(httpClientFactory);

        final ImmutableMap.Builder<Scheme, ClientFactory> builder = ImmutableMap.builder();
        for (ClientFactory f : Arrays.asList(httpClientFactory, thriftClientFactory)) {
            f.supportedSchemes().forEach(s -> builder.put(s, f));
        }

        clientFactories = builder.build();
        mainFactory = httpClientFactory;
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return clientFactories.keySet();
    }

    @Override
    public SessionOptions options() {
        return mainFactory.options();
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return mainFactory.eventLoopGroup();
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return mainFactory.eventLoopSupplier();
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);
        return clientFactories.get(scheme).newClient(uri, clientType, options);
    }

    @Override
    public void close() {
        // The global default should never be closed.
        if (this == ClientFactory.DEFAULT) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         ClientFactory.class.getSimpleName());
            return;
        }

        doClose();
    }

    void doClose() {
        clientFactories.values().forEach(ClientFactory::close);
    }
}
