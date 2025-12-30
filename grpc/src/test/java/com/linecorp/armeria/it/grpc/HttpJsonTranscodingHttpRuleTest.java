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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.api.HttpRule;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingOptions;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpJsonTranscodingHttpRuleTest {

    @RegisterExtension
    static final ServerExtension serverMixedRules = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HttpRule rule = HttpRule
                    .newBuilder()
                    .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                    .setPost("/additional/v2/messages/{message_id}")
                    .build();

            final HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.builder()
                                                                                 .additionalHttpRules(rule)
                                                                                 .build();

            sb.service(GrpcService.builder()
                                  .addService(new HttpJsonTranscodingTestService())
                                  .enableHttpJsonTranscoding(options)
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension serverOnlyAdditionalRules = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HttpRule rule = HttpRule
                    .newBuilder()
                    .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                    .setGet("/programmatic/messages/{message_id}")
                    .build();

            final HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.builder()
                                                                                 .useHttpAnnotations(false)
                                                                                 .additionalHttpRules(rule)
                                                                                 .build();

            sb.service(GrpcService.builder()
                                  .addService(new HttpJsonTranscodingTestService())
                                  .enableHttpJsonTranscoding(options)
                                  .build());
        }
    };

    @Test
    void bothAnnotationAndAdditionalRulesShouldCoexist() {
        final BlockingWebClient client = serverMixedRules.blockingWebClient();

        final AggregatedHttpResponse res1 = client.get("/v2/messages/123");
        assertThat(res1.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res1.contentUtf8()).node("text").isStringEqualTo("123:0::SIMPLE");

        final AggregatedHttpResponse res2 = client.prepare()
                                                  .post("/additional/v2/messages/456")
                                                  .content(MediaType.JSON_UTF_8, "{}")
                                                  .execute();
        assertThat(res2.status()).isEqualTo(HttpStatus.OK);
        assertThatJson(res2.contentUtf8()).node("text").isStringEqualTo("456:0::SIMPLE");
    }

    @Test
    void annotationBasedRulesShouldNotWorkWhenDisabled() {
        final AggregatedHttpResponse res = serverOnlyAdditionalRules.blockingWebClient()
                                                                    .get("/v2/messages/789");
        assertThat(res.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void additionalRulesShouldWorkWhenAnnotationsDisabled() {
        final AggregatedHttpResponse res = serverOnlyAdditionalRules.blockingWebClient()
                                                                    .get("/programmatic/messages/999");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(res.contentUtf8()).node("text").isStringEqualTo("999:0::SIMPLE");
    }
}
