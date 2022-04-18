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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.grpc.testing.Messages.CompressionType;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Codec.Gzip;
import io.grpc.Codec.Identity;
import io.grpc.DecompressorRegistry;
import io.grpc.StatusRuntimeException;

class GrpcClientCompressionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .build());
        }
    };

    @Test
    void compression() throws InterruptedException {
        final Gzip gzip = new Gzip();
        final TestServiceBlockingStub stub = GrpcClients.builder(server.httpUri())
                                                        .compressor(gzip)
                                                        .build(TestServiceBlockingStub.class);

        final Payload payload = Payload.newBuilder().setBody(ByteString.copyFromUtf8("Hello")).build();
        stub.unaryCall(SimpleRequest.newBuilder().setPayload(payload).build());
        ServiceRequestContext ctx = server.requestContextCaptor().take();
        RequestLog log = ctx.log().whenComplete().join();
        String encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isEqualTo(gzip.getMessageEncoding());

        // Override the client level compressor with CallOptions
        final TestServiceBlockingStub noCompression =
                stub.withCompression(Identity.NONE.getMessageEncoding());

        noCompression.unaryCall(SimpleRequest.newBuilder().setPayload(payload).build());
        ctx = server.requestContextCaptor().take();
        log = ctx.log().whenComplete().join();
        encoding = log.requestHeaders().get(GrpcHeaderNames.GRPC_ENCODING);
        assertThat(encoding).isNull();
    }

    @Test
    void decompressionRegistry() throws InterruptedException {
        final TestServiceBlockingStub stub = GrpcClients.builder(server.httpUri())
                                                        .decompressorRegistry(
                                                                DecompressorRegistry.emptyInstance())
                                                        .build(TestServiceBlockingStub.class);

        final Payload payload = Payload.newBuilder().setBody(ByteString.copyFromUtf8("Hello")).build();
        assertThatThrownBy(() -> {
            stub.unaryCall(SimpleRequest.newBuilder().setPayload(payload)
                                        // Unsupported compression type
                                        .setResponseCompression(CompressionType.GZIP)
                                        .build());
        }).isInstanceOf(StatusRuntimeException.class)
          .hasMessageContaining("Can't find decompressor for gzip");

        final TestServiceBlockingStub decompressingStub = GrpcClients.builder(server.httpUri())
                                                                     // Use the default DecompressorRegistry
                                                                     .build(TestServiceBlockingStub.class);

        final Payload payload0 = Payload.newBuilder().setBody(ByteString.copyFromUtf8("Hello")).build();
        final SimpleResponse response =
                decompressingStub.unaryCall(SimpleRequest.newBuilder().setPayload(payload0)
                                                         .setResponseCompression(CompressionType.GZIP)
                                                         .setResponseSize(100)
                                                         .build());
        assertThat(response.getPayload().getBody().toStringUtf8()).hasSize(100);
    }
}
