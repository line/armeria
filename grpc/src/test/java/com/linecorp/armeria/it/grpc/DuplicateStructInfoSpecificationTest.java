/*
 * Copyright 2023 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.ConsumesJson;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.grpc.Messages;

class DuplicateStructInfoSpecificationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new SimpleAnnotatedService());
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl(CommonPools.blockingTaskExecutor()))
                                  .enableUnframedRequests(true)
                                  .build());
            sb.serviceUnder("/docs", new DocService());
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void shouldHaveAliasInSpecification() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient();
        final JsonNode spec = client.prepare()
                                    .get("/docs/specification.json").asJson(JsonNode.class)
                                    .execute().content();
        boolean found = false;
        for (JsonNode struct : spec.get("structs")) {
            if ("armeria.grpc.testing.SimpleRequest".equals(struct.get("name").asText())) {
                found = true;
                assertThat(struct.get("alias").asText())
                        .isEqualTo("testing.grpc.Messages$SimpleRequest");
            }
        }
        assertThat(found)
                .describedAs("Failed to find a StructInfo for 'armeria.grpc.testing.SimpleRequest'")
                .isTrue();
    }

    private static class SimpleAnnotatedService {

        @ConsumesJson
        @ProducesJson
        @Post("/echo")
        public Messages.SimpleRequest echo(Messages.SimpleRequest request) {
            return request;
        }
    }
}
