/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.server.grpc.jsonname;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingOptions;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

class GrpcTranscodingJsonNameTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HttpJsonTranscodingOptions options =
                    HttpJsonTranscodingOptions.builder()
                                              .queryParamMatchRules(
                                                      HttpJsonTranscodingQueryParamMatchRule.IGNORE_JSON_NAME)
                                              .build();
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new HelloService())
                                                       .enableHttpJsonTranscoding(options)
                                                       .build();
            sb.service(grpcService);
        }
    };

    @Test
    void shouldIgnoreJsonNameOption() {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse response = client.get("/v1/hello/Armeria");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"message\":\"Hello, Armeria!\"}");
    }
}
