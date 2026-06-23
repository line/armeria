/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingOptions;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.ServiceDescriptor;
import io.grpc.stub.StreamObserver;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import testing.grpc.Transcoding.GetMessageRequestV1;
import testing.grpc.Transcoding.Message;

class HttpJsonTranscodingDefaultValueFieldsTest {

    private static final Function<ServiceDescriptor, GrpcJsonMarshaller> INCLUDING_DEFAULT_VALUE_FIELDS =
            serviceDescriptor -> GrpcJsonMarshaller.builder()
                                                   .jsonMarshallerCustomizer(
                                                           b -> b.includingDefaultValueFields(true))
                                                   .build(serviceDescriptor);

    @RegisterExtension
    static final ServerExtension serverIncludingDefaults = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpJsonTranscodingOptions options =
                    HttpJsonTranscodingOptions.builder()
                                              .jsonMarshallerFactory(INCLUDING_DEFAULT_VALUE_FIELDS)
                                              .build();
            sb.service(GrpcService.builder()
                                  .addService(new DefaultValueTestService())
                                  .enableHttpJsonTranscoding(options)
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension serverOmittingDefaults = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new DefaultValueTestService())
                                  .enableHttpJsonTranscoding(true)
                                  .build());
        }
    };

    @Test
    void includesDefaultValueFieldsWhenEnabled() {
        final AggregatedHttpResponse res = serverIncludingDefaults.blockingWebClient().get("/v1/messages/1");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("text").isStringEqualTo("");
    }

    @Test
    void omitsDefaultValueFieldsByDefault() {
        final AggregatedHttpResponse res = serverOmittingDefaults.blockingWebClient().get("/v1/messages/1");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res.contentUtf8()).node("text").isAbsent();
    }

    private static final class DefaultValueTestService extends HttpJsonTranscodingTestServiceImplBase {
        @Override
        public void getMessageV1(GetMessageRequestV1 request, StreamObserver<Message> responseObserver) {
            responseObserver.onNext(Message.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
