/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

/**
 * Creates a new Thrift client that connects to a {@link URI} or an {@link EndpointGroup}.
 */
@UnstableApi
public final class ThriftClients {

    /**
     * Creates a new Thrift client that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new Thrift client
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the specified
     *                                  {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(String uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new Thrift client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @param uri the {@link URI} of the server endpoint
     * @param clientType the type of the new Thrift client
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the specified
     *                                  {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(URI uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new Thrift client that connects to the specified {@link EndpointGroup} with the specified
     * {@code scheme} using the default {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @param scheme the {@link Scheme} represented as a {@link String}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new Thrift client
     *
     * @throws IllegalArgumentException if the specified {@code scheme} is invalid or the specified
     *                                  {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(String scheme, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(scheme, endpointGroup).build(clientType);
    }

    /**
     * Creates a new Thrift client that connects to the specified {@link EndpointGroup} with the specified
     * {@link Scheme} using the default {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @param scheme the {@link Scheme}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new Thrift client
     *
     * @throws IllegalArgumentException if the specified {@link Scheme} is invalid or the specified
     *                                  {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(Scheme scheme, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(scheme, endpointGroup).build(clientType);
    }

    /**
     * Creates a new Thrift client that connects to the specified {@link EndpointGroup} with the specified
     * {@code scheme} and {@code path} using the default {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @param scheme the {@link Scheme} represented as a {@link String}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the specified {@link Scheme} is invalid or the specified
     *                                  {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(String scheme, EndpointGroup endpointGroup, String path,
                                  Class<T> clientType) {
        return builder(scheme, endpointGroup).path(path).build(clientType);
    }

    /**
     * Creates a new Thrift client that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} and {@link ThriftSerializationFormats#BINARY} using the default
     * {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new Thrift client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(SessionProtocol protocol, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(protocol, endpointGroup).build(clientType);
    }

    /**
     * Creates a new client that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol}, {@code path} and {@link ThriftSerializationFormats#BINARY}
     * using the default {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(SessionProtocol protocol, EndpointGroup endpointGroup, String path,
                                  Class<T> clientType) {
        return builder(protocol, endpointGroup).path(path).build(clientType);
    }

    /**
     * Creates a new client that is configured with the specified {@link RpcPreprocessor} using
     * {@link ThriftSerializationFormats#BINARY} and the default {@link ClientFactory}.
     *
     * @param rpcPreprocessor the {@link RpcPreprocessor}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(RpcPreprocessor rpcPreprocessor, Class<T> clientType) {
        return builder(rpcPreprocessor).build(clientType);
    }

    /**
     * Creates a new client that is configured with the specified {@link SerializationFormat} and
     * {@link RpcPreprocessor} using the default {@link ClientFactory}.
     *
     * @param serializationFormat the {@link SerializationFormat}
     * @param rpcPreprocessor the {@link RpcPreprocessor}
     * @param clientType the type of the new client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(SerializationFormat serializationFormat,
                                  RpcPreprocessor rpcPreprocessor, Class<T> clientType) {
        return builder(serializationFormat, rpcPreprocessor).build(clientType);
    }

    /**
     * Creates a new client that is configured with the specified {@link SerializationFormat},
     * {@link RpcPreprocessor} and {@code path} using the default {@link ClientFactory}.
     *
     * @param serializationFormat the {@link SerializationFormat}
     * @param rpcPreprocessor the {@link RpcPreprocessor}
     * @param clientType the type of the new client
     * @param path the path
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported Thrift client stub.
     */
    public static <T> T newClient(SerializationFormat serializationFormat,
                                  RpcPreprocessor rpcPreprocessor, Class<T> clientType,
                                  String path) {
        return builder(serializationFormat, rpcPreprocessor).path(path).build(clientType);
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the client that connects to the specified
     * {@code uri}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the {@code uri}'s scheme
     *                                  contains an invalid {@link SerializationFormat}.
     */
    public static ThriftClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the client that connects to the specified
     * {@link URI}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@link URI}, {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @throws IllegalArgumentException if the specified {@link URI} is invalid, or the {@link URI}'s scheme
     *                                  contains an invalid {@link SerializationFormat}.
     */
    public static ThriftClientBuilder builder(URI uri) {
        return new ThriftClientBuilder(requireNonNull(uri, "uri"));
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@code scheme}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the given {@code scheme},
     * {@link ThriftSerializationFormats#BINARY} will be used by default.
     *
     * @throws IllegalArgumentException if the {@code scheme} is invalid.
     */
    public static ThriftClientBuilder builder(String scheme, EndpointGroup endpointGroup) {
        return builder(Scheme.parse(requireNonNull(scheme, "scheme")), endpointGroup);
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the Thrift client that connects
     * to the specified {@link EndpointGroup} with the specified {@link SessionProtocol} and
     * {@link ThriftSerializationFormats#BINARY}.
     */
    public static ThriftClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(Scheme.of(ThriftSerializationFormats.BINARY, requireNonNull(protocol, "protocol")),
                       endpointGroup);
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@link Scheme}.
     *
     * <p>Note that if {@link SerializationFormat#NONE} is specified in the {@link Scheme},
     * {@link ThriftSerializationFormats#BINARY} will be used by default.
     */
    public static ThriftClientBuilder builder(Scheme scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        return new ThriftClientBuilder(scheme, endpointGroup);
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the client that is configured with
     * the specified {@link RpcPreprocessor} using {@link ThriftSerializationFormats#BINARY}.
     */
    public static ThriftClientBuilder builder(RpcPreprocessor rpcPreprocessor) {
        return new ThriftClientBuilder(SerializationFormat.NONE,
                                       requireNonNull(rpcPreprocessor, "rpcPreprocessor"));
    }

    /**
     * Returns a new {@link ThriftClientBuilder} that builds the client that is configured with
     * the specified {@link RpcPreprocessor}.
     *
     * <p>Note that if {@link SerializationFormat#NONE} is specified
     * {@link ThriftSerializationFormats#BINARY} will be used by default.
     */
    public static ThriftClientBuilder builder(SerializationFormat serializationFormat,
                                              RpcPreprocessor rpcPreprocessor) {
        requireNonNull(serializationFormat, "serializationFormat");
        requireNonNull(rpcPreprocessor, "rpcPreprocessor");
        return new ThriftClientBuilder(serializationFormat, rpcPreprocessor);
    }

    private ThriftClients() {}
}
