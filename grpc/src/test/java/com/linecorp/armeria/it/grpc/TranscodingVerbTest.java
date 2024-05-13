/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.it.grpc;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.TranscodingVerbServiceGrpc;
import testing.grpc.Verb.Simple;

class TranscodingVerbTest {

    static class HttpJsonTranscodingTestService extends TranscodingVerbServiceGrpc.TranscodingVerbServiceImplBase {
        @Override
        public void wildcardVerb(Simple request, StreamObserver<Simple> responseObserver) {
            responseObserver.onNext(request);
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new HttpJsonTranscodingTestService())
                                  .enableHttpJsonTranscoding(true)
                                  .build());
        }
    };

    @Test
    void testWildcardVerb() throws Exception {
        final AggregatedHttpResponse response = server.blockingWebClient().get("/v1/wildcard/verb/hello/world:verb");
        assertThatJson(response.contentUtf8()).node("name").isEqualTo("hello/world");
    }
}
