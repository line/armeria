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
package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.CustomVerbServiceBindingTestServiceGrpc.CustomVerbServiceBindingTestServiceImplBase;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class HttpJsonTranscodingVerbServiceBindingTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new CustomVerbServiceBindingTestServiceImpl())
                                  .enableHttpJsonTranscoding(true)
                                  .build());
        }
    };

    private static class CustomVerbServiceBindingTestServiceImpl
            extends CustomVerbServiceBindingTestServiceImplBase {

        @Override
        public void foo(NameRequest request,
                        StreamObserver<MethodNameReply> responseObserver) {
            reply(request, responseObserver, "foo");
        }

        private static void reply(NameRequest request,
                                  StreamObserver<MethodNameReply> responseObserver,
                                  String message) {
            if (!"a/b".equals(request.getName())) {
                responseObserver.onError(new IllegalArgumentException("unexpected name: " + request.getName()));
            } else {
                responseObserver.onNext(MethodNameReply.newBuilder().setMessage(message).build());
                responseObserver.onCompleted();
            }
        }

        @Override
        public void bar(NameRequest request,
                        StreamObserver<MethodNameReply> responseObserver) {
            reply(request, responseObserver, "bar");
        }

        @Override
        public void fooCustomVerb(NameRequest request,
                                  StreamObserver<MethodNameReply> responseObserver) {
            reply(request, responseObserver, "fooCustomVerb");
        }

        @Override
        public void barCustomVerb(NameRequest request,
                                  StreamObserver<MethodNameReply> responseObserver) {
            reply(request, responseObserver, "barCustomVerb");
        }

        @Override
        public void fooCustomVerb2(NameRequest request,
                                   StreamObserver<MethodNameReply> responseObserver) {
            reply(request, responseObserver, "fooCustomVerb2");
        }

        @Override
        public void barCustomVerb2(NameRequest request,
                                  StreamObserver<MethodNameReply> responseObserver) {
            reply(request, responseObserver, "barCustomVerb2");
        }
    }

    @CsvSource({
            "/v1/foo/a/b, foo",
            "/v1/foo/a/b:verb, fooCustomVerb",
            "/v1/foo/a/b:verb2, fooCustomVerb2",
            "/v1/bar/a/b, bar",
            "/v1/bar/a/b:verb, barCustomVerb",
            "/v1/bar/a/b:verb2, barCustomVerb2"
    })
    @ParameterizedTest
    void customVerbTakes(String path, String expectedMessage) {
        final String actual = server.blockingWebClient().get(path).contentUtf8();
        assertThat(actual).isEqualTo("{\"message\":\"" + expectedMessage + "\"}");
    }
}
