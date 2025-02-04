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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.JSON_NAME;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.LOWER_CAMEL_CASE;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule.ORIGINAL_FIELD;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.jsonname.JsonNameReply;
import testing.grpc.jsonname.JsonNameRequest;
import testing.grpc.jsonname.JsonNameTestServiceGrpc;

class GrpcTranscodingJsonNameTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final JsonNameTestService jsonNameService = new JsonNameTestService();
            final HashMap<String, HttpJsonTranscodingOptions> transcodingOptions = new HashMap<>();
            transcodingOptions.put("/original",
                                   HttpJsonTranscodingOptions.builder()
                                                             .queryParamMatchRules(ORIGINAL_FIELD)
                                                             .build());
            transcodingOptions.put("/camel",
                                   HttpJsonTranscodingOptions.builder()
                                                             .queryParamMatchRules(LOWER_CAMEL_CASE)
                                                             .build());
            transcodingOptions.put("/jsonNameOrOriginal",
                                   HttpJsonTranscodingOptions.builder()
                                                             .queryParamMatchRules(ORIGINAL_FIELD, JSON_NAME)
                                                             .build());
            transcodingOptions.put("/jsonNameOrCamel",
                                   HttpJsonTranscodingOptions.builder()
                                                             .queryParamMatchRules(LOWER_CAMEL_CASE, JSON_NAME)
                                                             .build());
            transcodingOptions.put("/all",
                                   HttpJsonTranscodingOptions.builder()
                                                             .queryParamMatchRules(ORIGINAL_FIELD,
                                                                                   LOWER_CAMEL_CASE, JSON_NAME)
                                                             .build());
            transcodingOptions.forEach((path, options) -> {
                sb.serviceUnder(path, GrpcService.builder()
                                                 .addService(jsonNameService)
                                                 .enableHttpJsonTranscoding(options)
                                                 .build());
            });
        }
    };

    @CsvSource({
            // ORIGINAL_FIELD: Use the original fields
            "/original, json_name_query_param=third&query_param=fourth, first|second|third|fourth",
            // ORIGINAL_FIELD: Ignore the json name
            "/original, customJsonNameQueryParam=third&query_param=fourth, first|second||fourth",
            // ORIGINAL_FIELD: Ignore the camel case
            "/original, json_name_query_param=third&queryParam=fourth, first|second|third|",
            // LOWER_CAMEL_CASE: Use the camel case
            "/camel, jsonNameQueryParam=third&queryParam=fourth, first|second|third|fourth",
            // LOWER_CAMEL_CASE: Ignore the json name
            "/camel, customJsonNameQueryParam=third&queryParam=fourth, first|second||fourth",
            // LOWER_CAMEL_CASE: Ignore the original fields
            "/camel, json_name_query_param=third&query_param=fourth, first|second||",
            // ORIGINAL, JSON_NAME: Use the original fields
            "/jsonNameOrOriginal, json_name_query_param=third&query_param=fourth, first|second|third|fourth",
            // ORIGINAL, JSON_NAME: Use both the original fields and json names
            "/jsonNameOrOriginal, customJsonNameQueryParam=third&query_param=fourth, first|second|third|fourth",
            // ORIGINAL, JSON_NAME: Ignore the camel case
            "/jsonNameOrOriginal, customJsonNameQueryParam=third&queryParam=fourth, first|second|third|",
            // ORIGINAL, JSON_NAME: Ignore the camel case
            "/jsonNameOrOriginal, jsonNameQueryParam=third&query_param=fourth, first|second||fourth",
            // LOWER_CAMEL_CASE, JSON_NAME: Use the camel case
            "/jsonNameOrCamel, jsonNameQueryParam=third&queryParam=fourth, first|second|third|fourth",
            // LOWER_CAMEL_CASE, JSON_NAME: Use both the camel case and json names
            "/jsonNameOrCamel, customJsonNameQueryParam=third&queryParam=fourth, first|second|third|fourth",
            // LOWER_CAMEL_CASE, JSON_NAME: Ignore the original fields
            "/jsonNameOrCamel, customJsonNameQueryParam=third&query_param=fourth, first|second|third|",
            // LOWER_CAMEL_CASE, JSON_NAME: Ignore the original fields
            "/jsonNameOrCamel, json_name_query_param=third&queryParam=fourth, first|second||fourth",
            // ALL: Use the camel case
            "/all, jsonNameQueryParam=third&queryParam=fourth, first|second|third|fourth",
            // ALL: Use both the camel case and json names
            "/all, customJsonNameQueryParam=third&queryParam=fourth, first|second|third|fourth",
            // ALL: Use both the original fields and the camel case
            "/all, customJsonNameQueryParam=third&query_param=fourth, first|second|third|fourth",
            // ALL: Use the original fields
            "/all, json_name_query_param=third&query_param=fourth, first|second|third|fourth",
    })
    @ParameterizedTest
    void testOriginalField(String prefix, String query, String expected) {
        final BlockingWebClient client = server.blockingWebClient();
        final AggregatedHttpResponse response = client.get(prefix + "/v1/hello/first/second?" + query);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(response.contentUtf8())
                .isEqualTo("{\"message\": \"" + expected + "\"}");
    }

    private static final class JsonNameTestService extends JsonNameTestServiceGrpc.JsonNameTestServiceImplBase {
        @Override
        public void hello(JsonNameRequest request, StreamObserver<JsonNameReply> responseObserver) {
            final String message = request.getJsonNamePathVariable() + '|' +
                                   request.getPathVariable() + '|' +
                                   request.getJsonNameQueryParam() + '|' +
                                   request.getQueryParam();
            final JsonNameReply reply = JsonNameReply.newBuilder()
                                                     .setMessage(message)
                                                     .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
}
