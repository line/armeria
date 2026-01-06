/*
 * Copyright 2025 LY Corporation
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

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.HttpRule;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingOptions;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpJsonTranscodingHttpRuleTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HttpRule rule1 = HttpRule
                    .newBuilder()
                    .setSelector("armeria.grpc.testing.TestService.UnaryCall")
                    .setPost("/test/unaryCall")
                    .setBody("*")
                    .build();
            final HttpJsonTranscodingOptions options1 = HttpJsonTranscodingOptions.builder()
                                                                                  .additionalHttpRules(rule1)
                                                                                  .build();
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(newSingleThreadScheduledExecutor()))
                                  .enableHttpJsonTranscoding(options1)
                                  .build());

            final HttpRule rule2 = HttpRule
                    .newBuilder()
                    .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                    .setGet("/programmatic/messages/{message_id}")
                    .build();
            final HttpJsonTranscodingOptions options2 = HttpJsonTranscodingOptions.builder()
                                                                                  .ignoreProtoHttpRule(true)
                                                                                  .additionalHttpRules(rule2)
                                                                                  .build();
            sb.service(GrpcService.builder()
                                  .addService(new HttpJsonTranscodingTestService())
                                  .enableHttpJsonTranscoding(options2)
                                  .build());
        }
    };

    @Test
    void additionalRuleShouldWorkForMethodWithoutProtoHttpOption() throws Exception {
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .prepare()
                                                 .post("/test/unaryCall")
                                                 .content(MediaType.JSON_UTF_8, "{\"responseSize\":10}")
                                                 .execute();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        final JsonNode json = JacksonUtil.newDefaultObjectMapper().readTree(res.contentUtf8());
        final String base64Body = json.get("payload").get("body").asText();
        final byte[] decodedBody = Base64.getDecoder().decode(base64Body);
        assertThat(decodedBody).hasSize(10);
    }

    @Test
    void annotationBasedRulesShouldNotWorkWhenDisabled() {
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .get("/v2/messages/123");
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void additionalRulesShouldWorkWhenAnnotationsDisabled() {
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .get("/programmatic/messages/999");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).node("text").isStringEqualTo("999:0::SIMPLE");
    }
}
