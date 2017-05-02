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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.Service;

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ServerServiceDefinition;

/**
 * Constructs a {@link GrpcService} to serve GRPC services from within Armeria.
 */
public final class GrpcServiceBuilder {

    private final HandlerRegistry.Builder registryBuilder =
            new HandlerRegistry.Builder();

    @Nullable
    private DecompressorRegistry decompressorRegistry;

    @Nullable
    private CompressorRegistry compressorRegistry;

    private int maxInboundMessageSizeBytes = GrpcService.NO_MAX_INBOUND_MESSAGE_SIZE;

    private int maxOutboundMessageSizeBytes = ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE;

    private boolean enableUnframedRequests;

    /**
     * Adds a GRPC {@link ServerServiceDefinition} to this {@link GrpcServiceBuilder}, such as
     * what's returned by {@link BindableService#bindService()}.
     */
    public GrpcServiceBuilder addService(ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(service, "service"));
        return this;
    }

    /**
     * Adds a GRPC {@link BindableService} to this {@link GrpcServiceBuilder}. Most GRPC service
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
     * Sets the maximum size in bytes of an individual incoming message. If not set, will use
     * {@link ServerConfig#defaultMaxRequestLength}. To support long-running RPC streams, it is recommended to
     * set {@link ServerConfig#defaultMaxRequestLength} and {@link ServerConfig#defaultRequestTimeoutMillis} to
     * very high values and set this to the expected limit of individual messages in the stream.
     */
    public void setMaxInboundMessageSizeBytes(int maxInboundMessageSizeBytes) {
        checkArgument(maxInboundMessageSizeBytes > 0,
                      "maxInboundMessageSizeBytes must be >0");
        this.maxInboundMessageSizeBytes = maxInboundMessageSizeBytes;
    }

    /**
     * Sets the maximum size in bytes of an individual outgoing message. If not set, all messages will be sent.
     * This can be a safety valve to prevent overflowing network connections with large messages due to business
     * logic bugs.
     */
    public void setMaxOutboundMessageSizeBytes(int maxOutboundMessageSizeBytes) {
        checkArgument(maxOutboundMessageSizeBytes > 0,
                      "maxOutboundMessageSizeBytes must be >0");
        this.maxOutboundMessageSizeBytes = maxOutboundMessageSizeBytes;
    }

    /**
     * Sets whether the service handles requests not framed using the GRPC wire protocol. Such requests should
     * only have the serialized message as the request content, and the response content will only have the
     * serialized response message. Supporting unframed requests can be useful, for example, when migrating an
     * existing service to GRPC.
     *
     * <p>Limitations:
     * <ul>
     *     <li>Only unary methods (single request, single response) are supported.</li>
     *     <li>
     *         Message compression is not supported.
     *         {@link com.linecorp.armeria.server.http.encoding.HttpEncodingService} should be used instead for
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
     * {@link com.linecorp.armeria.server.ServerBuilder}. As GRPC services themselves are mounted at a path that
     * corresponds to their protobuf package, you will almost always want to bind to a prefix, e.g. by using
     * {@link com.linecorp.armeria.server.ServerBuilder#serviceUnder(String, Service)}.
     */
    public Service<HttpRequest, HttpResponse> build() {
        GrpcService grpcService = new GrpcService(
                registryBuilder.build(),
                firstNonNull(decompressorRegistry, DecompressorRegistry.getDefaultInstance()),
                firstNonNull(compressorRegistry, CompressorRegistry.getDefaultInstance()),
                maxOutboundMessageSizeBytes,
                maxInboundMessageSizeBytes);
        return enableUnframedRequests ? grpcService.decorate(UnframedGrpcService::new) : grpcService;
    }
}
