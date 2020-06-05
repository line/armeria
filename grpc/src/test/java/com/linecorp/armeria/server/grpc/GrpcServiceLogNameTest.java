/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class GrpcServiceLogNameTest {

    private static ServiceRequestContext capturedCtx;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .build());
            sb.decorator((delegate, ctx, req) -> {
                capturedCtx = ctx;
                return delegate.serve(ctx, req);
            });
        }
    };

    @Test
    void logName() {
        final TestServiceBlockingStub client = Clients.builder(server.httpUri(GrpcSerializationFormats.PROTO))
                                                      .build(TestServiceBlockingStub.class);
        client.emptyCall(Empty.newBuilder().build());
        final RequestLog log = capturedCtx.log().partial();
        assertThat(log.serviceName()).isEqualTo(TestServiceGrpc.SERVICE_NAME);
        assertThat(log.name()).isEqualTo("EmptyCall");
        assertThat(log.fullName()).isEqualTo(TestServiceGrpc.getEmptyCallMethod().getFullMethodName());
    }
}
