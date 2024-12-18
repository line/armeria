/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;

/**
 * Creates a new gRPC client that connects to a {@link URI} or an {@link EndpointGroup}.
 */
@UnstableApi
public final class GrpcClients {

    /**
     * Creates a new gRPC client that connects to the specified {@code uri} using the default
     * {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @param uri the URI of the server endpoint
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the specified
     *                                  {@code clientType} is an unsupported gRPC client stub.
     */
    public static <T> T newClient(String uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new gRPC client that connects to the specified {@link URI} using the default
     * {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@link URI}, {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @param uri the {@link URI} of the server endpoint
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the specified
     *                                  {@code clientType} is an unsupported gRPC client stub.
     */
    public static <T> T newClient(URI uri, Class<T> clientType) {
        return builder(uri).build(clientType);
    }

    /**
     * Creates a new gRPC client that connects to the specified {@link EndpointGroup} with the specified
     * {@code scheme} using the default {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@link URI}, {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @param scheme the {@link Scheme} represented as a {@link String}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the specified {@code scheme} is invalid or the specified
     *                                  {@code clientType} is an unsupported gRPC client stub.
     */
    public static <T> T newClient(String scheme, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(scheme, endpointGroup).build(clientType);
    }

    /**
     * Creates a new gRPC client that connects to the specified {@link EndpointGroup} with the specified
     * {@link Scheme} using the default {@link ClientFactory}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@link URI}, {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @param scheme the {@link Scheme}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the specified {@code clientType} is unsupported for
     *                                  the specified {@link Scheme}.
     */
    public static <T> T newClient(Scheme scheme, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(scheme, endpointGroup).build(clientType);
    }

    /**
     * Creates a new gRPC client that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} and {@link GrpcSerializationFormats#PROTO} using the default
     * {@link ClientFactory}.
     *
     * @param protocol the {@link SessionProtocol}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported gRPC client stub.
     */
    public static <T> T newClient(SessionProtocol protocol, EndpointGroup endpointGroup, Class<T> clientType) {
        return builder(protocol, endpointGroup).build(clientType);
    }

    /**
     /**
     * Creates a new gRPC client that is configured with the specified {@link HttpPreprocessor} using
     * {@link GrpcSerializationFormats#PROTO} and the default {@link ClientFactory}.
     *
     * @param httpPreprocessor the {@link HttpPreprocessor}
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported gRPC client stub.
     */
    public static <T> T newClient(HttpPreprocessor httpPreprocessor, Class<T> clientType) {
        return newClient(SerializationFormat.NONE, httpPreprocessor, clientType);
    }

    /**
     * Creates a new gRPC client that is configured with the specified {@link HttpPreprocessor}
     * and {@link SerializationFormat} using the default {@link ClientFactory}.
     *
     * @param serializationFormat the {@link SerializationFormat}
     * @param httpPreprocessor the {@link HttpPreprocessor}
     * @param clientType the type of the new gRPC client
     *
     * @throws IllegalArgumentException if the {@code clientType} is an unsupported gRPC client stub.
     */
    public static <T> T newClient(SerializationFormat serializationFormat, HttpPreprocessor httpPreprocessor,
                                  Class<T> clientType) {
        return builder(serializationFormat, httpPreprocessor).build(clientType);
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the client that connects to the specified
     * {@code uri}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@code uri}, {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @throws IllegalArgumentException if the specified {@code uri} is invalid, or the {@code uri}'s scheme
     *                                  contains an invalid {@link SerializationFormat}.
     */
    public static GrpcClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the client that connects to the specified
     * {@link URI}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the {@link Scheme} component of
     * the {@link URI}, {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @throws IllegalArgumentException if the specified {@link URI} is invalid, or the {@link URI}'s scheme
     *                                  contains an invalid {@link SerializationFormat}.
     */
    public static GrpcClientBuilder builder(URI uri) {
        return new GrpcClientBuilder(requireNonNull(uri, "uri"));
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@code scheme}.
     *
     * <p>Note that if a {@link SerializationFormat} is not specified in the given {@code scheme},
     * {@link GrpcSerializationFormats#PROTO} will be used by default.
     *
     * @throws IllegalArgumentException if the {@code scheme} is invalid.
     */
    public static GrpcClientBuilder builder(String scheme, EndpointGroup endpointGroup) {
        return builder(Scheme.parse(requireNonNull(scheme, "scheme")), endpointGroup);
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the gRPC client that connects
     * to the specified {@link EndpointGroup} with the specified {@link SessionProtocol} and
     * {@link GrpcSerializationFormats#PROTO}.
     */
    public static GrpcClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(Scheme.of(GrpcSerializationFormats.PROTO, requireNonNull(protocol, "protocol")),
                       endpointGroup);
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the client that connects to the specified
     * {@link EndpointGroup} with the specified {@link Scheme}.
     *
     * <p>Note that if {@link SerializationFormat#NONE} is specified in the {@link Scheme},
     * {@link GrpcSerializationFormats#PROTO} will be used by default.
     */
    public static GrpcClientBuilder builder(Scheme scheme, EndpointGroup endpointGroup) {
        requireNonNull(scheme, "scheme");
        requireNonNull(endpointGroup, "endpointGroup");
        return new GrpcClientBuilder(scheme, endpointGroup);
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the client that is configured with
     * {@link HttpPreprocessor}.
     *
     * <p>Note that {@link GrpcSerializationFormats#PROTO} will be used by default.
     */
    public static GrpcClientBuilder builder(HttpPreprocessor httpPreprocessor) {
        requireNonNull(httpPreprocessor, "httpPreprocessor");
        return builder(SerializationFormat.NONE, httpPreprocessor);
    }

    /**
     * Returns a new {@link GrpcClientBuilder} that builds the client that is configured with
     * the specified {@link HttpPreprocessor} and {@link SerializationFormat}.
     *
     * <p>Note that if {@link SerializationFormat#NONE} is specified,
     * {@link GrpcSerializationFormats#PROTO} will be used by default.
     */
    public static GrpcClientBuilder builder(SerializationFormat serializationFormat,
                                            HttpPreprocessor httpPreprocessor) {
        requireNonNull(serializationFormat, "serializationFormat");
        requireNonNull(httpPreprocessor, "httpPreprocessor");
        return new GrpcClientBuilder(serializationFormat, httpPreprocessor);
    }

    private GrpcClients() {}
}
