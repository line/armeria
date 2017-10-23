/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.encoding.HttpEncodingService;

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ServerServiceDefinition;

/**
 * Constructs a {@link GrpcService} to serve gRPC services from within Armeria.
 */
public final class GrpcServiceBuilder {

    private static final Set<SerializationFormat> DEFAULT_SUPPORTED_SERIALIZATION_FORMATS =
            ImmutableSet.of(GrpcSerializationFormats.PROTO, GrpcSerializationFormats.PROTO_WEB);

    private final HandlerRegistry.Builder registryBuilder = new HandlerRegistry.Builder();

    @Nullable
    private DecompressorRegistry decompressorRegistry;

    @Nullable
    private CompressorRegistry compressorRegistry;

    private Set<SerializationFormat> supportedSerializationFormats = DEFAULT_SUPPORTED_SERIALIZATION_FORMATS;

    private int maxInboundMessageSizeBytes = GrpcService.NO_MAX_INBOUND_MESSAGE_SIZE;

    private int maxOutboundMessageSizeBytes = ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE;

    private boolean enableUnframedRequests;

    /**
     * Adds a gRPC {@link ServerServiceDefinition} to this {@link GrpcServiceBuilder}, such as
     * what's returned by {@link BindableService#bindService()}.
     */
    public GrpcServiceBuilder addService(ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(service, "service"));
        return this;
    }

    /**
     * Adds a gRPC {@link BindableService} to this {@link GrpcServiceBuilder}. Most gRPC service
     * implementations are {@link BindableService}s.
     */
    public GrpcServiceBuilder addService(BindableService bindableService) {
        return addService(bindableService.bindService());
    }

    /**
     * Sets the {@link DecompressorRegistry} to use when decompressing messages. If not set, will use
     * the default, which supports gzip only.
     */
    public GrpcServiceBuilder decompressorRegistry(DecompressorRegistry registry) {
        decompressorRegistry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Sets the {@link CompressorRegistry} to use when compressing messages. If not set, will use the
     * default, which supports gzip only.
     */
    public GrpcServiceBuilder compressorRegistry(CompressorRegistry registry) {
        compressorRegistry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Sets the {@link SerializationFormat}s supported by this server. If not set, defaults to supporting binary
     * protobuf formats. Enabling JSON can be useful, e.g., when migrating existing JSON services to gRPC.
     */
    public GrpcServiceBuilder supportedSerializationFormats(SerializationFormat... formats) {
        return supportedSerializationFormats(ImmutableSet.copyOf(requireNonNull(formats, "formats")));
    }

    /**
     * Sets the {@link SerializationFormat}s supported by this server. If not set, defaults to supporting binary
     * protobuf formats. JSON formats are currently very inefficient and not recommended for use in production.
     *
     * <p>TODO(anuraaga): Use faster JSON marshalling.
     */
    public GrpcServiceBuilder supportedSerializationFormats(Iterable<SerializationFormat> formats) {
        requireNonNull(formats, "formats");
        for (SerializationFormat format : formats) {
            if (!GrpcSerializationFormats.isGrpc(format)) {
                throw new IllegalArgumentException("Not a gRPC serialization format: " + format);
            }
        }
        supportedSerializationFormats = ImmutableSet.copyOf(formats);
        return this;
    }

    /**
     * Sets the maximum size in bytes of an individual incoming message. If not set, will use
     * {@link ServerConfig#defaultMaxRequestLength}. To support long-running RPC streams, it is recommended to
     * set {@link ServerConfig#defaultMaxRequestLength} and {@link ServerConfig#defaultRequestTimeoutMillis} to
     * very high values and set this to the expected limit of individual messages in the stream.
     */
    public GrpcServiceBuilder setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        checkArgument(maxInboundMessageSizeBytes > 0,
                      "maxInboundMessageSizeBytes must be >0");
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
        return this;
    }

    /**
     * Sets the maximum size in bytes of an individual outgoing message. If not set, all messages will be sent.
     * This can be a safety valve to prevent overflowing network connections with large messages due to business
     * logic bugs.
     */
    public GrpcServiceBuilder setMaxOutboundMessageSizeBytes(int maxOutboundMessageSizeBytes) {
        checkArgument(maxOutboundMessageSizeBytes > 0,
                      "maxOutboundMessageSizeBytes must be >0");
        this.maxOutboundMessageSizeBytes = maxOutboundMessageSizeBytes;
        return this;
    }

    /**
     * Sets whether the service handles requests not framed using the gRPC wire protocol. Such requests should
     * only have the serialized message as the request content, and the response content will only have the
     * serialized response message. Supporting unframed requests can be useful, for example, when migrating an
     * existing service to gRPC.
     *
     * <p>Limitations:
     * <ul>
     *     <li>Only unary methods (single request, single response) are supported.</li>
     *     <li>
     *         Message compression is not supported.
     *         {@link HttpEncodingService} should be used instead for
     *         transport level encoding.
     *     </li>
     * </ul>
     */
    public GrpcServiceBuilder enableUnframedRequests(boolean enableUnframedRequests) {
        this.enableUnframedRequests = enableUnframedRequests;
        return this;
    }

    /**
     * Constructs a new {@link GrpcService} that can be bound to
     * {@link com.linecorp.armeria.server.ServerBuilder}. As gRPC services themselves are mounted at a path that
     * corresponds to their protobuf package, you will almost always want to bind to a prefix, e.g. by using
     * {@link com.linecorp.armeria.server.ServerBuilder#serviceUnder(String, Service)}.
     */
    public Service<HttpRequest, HttpResponse> build() {
        HandlerRegistry handlerRegistry = registryBuilder.build();
        GrpcService grpcService = new GrpcService(
                handlerRegistry,
                handlerRegistry
                      .methods()
                      .keySet()
                      .stream()
                      .map(path -> PathMapping.ofExact("/" + path))
                      .collect(ImmutableSet.toImmutableSet()),
                firstNonNull(decompressorRegistry, DecompressorRegistry.getDefaultInstance()),
                firstNonNull(compressorRegistry, CompressorRegistry.getDefaultInstance()),
                supportedSerializationFormats, maxOutboundMessageSizeBytes,
                maxInboundMessageSizeBytes);
        return enableUnframedRequests ? grpcService.decorate(UnframedGrpcService::new) : grpcService;
    }
}
