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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;

class GrpcClientTrailersTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                                  .build())
              .build();
        }
    };

    @Test
    void requestOneAndReceiveMessageAndTrailers() {
        final TestServiceStub client = GrpcClients.builder(server.httpUri())
                                                  .build(TestServiceStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> unaryCall =
                client.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);

        final AtomicReference<Metadata> trailersRef = new AtomicReference<>();
        final AtomicReference<Status> statusRef = new AtomicReference<>();
        final AtomicReference<SimpleResponse> responseRef = new AtomicReference<>();
        unaryCall.start(new Listener<SimpleResponse>() {
            @Override
            public void onHeaders(Metadata headers) {}

            @Override
            public void onMessage(SimpleResponse message) {
                responseRef.set(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                statusRef.set(status);
                trailersRef.set(trailers);
            }
        }, new Metadata());

        unaryCall.sendMessage(SimpleRequest.getDefaultInstance());
        unaryCall.halfClose();
        // Send 1 request and expect to receive a message and trailers
        unaryCall.request(1);

        await().untilAtomic(trailersRef, Matchers.notNullValue());
        assertThat(responseRef.get()).isNotNull();
        assertThat(statusRef.get().getCode()).isEqualTo(Code.OK);
    }
}
