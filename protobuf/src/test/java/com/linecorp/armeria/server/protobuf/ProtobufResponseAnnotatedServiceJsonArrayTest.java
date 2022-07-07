/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.protobuf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.protobuf.testing.Messages;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Given a ProtobufResponseConverterFunction with a custom JsonPrinter configured via the service builder,
 * the produced JSON should show that the custom JsonPrinter was used.
 */
class ProtobufResponseAnnotatedServiceJsonArrayTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new GreetingService(), new ProtobufResponseConverterFunction(
                    JsonFormat.printer().includingDefaultValueFields()));
        }
    };

    WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @Test
    void returnEmptyJsonArrayGivenCustomJsonPrinter() {
        final AggregatedHttpResponse response = client.get("/json").aggregate().join();
        assertThat(Objects.requireNonNull(Objects.requireNonNull(response.headers().contentType()))
                          .subtype()).isEqualTo("json");

        final String responseString = response.contentUtf8();

        final String expectedString = "{\"messages\":[]}";
        assertThat(responseString).isEqualToIgnoringWhitespace(expectedString);
    }

    private static class GreetingService {
        @Get("/json")
        @ProducesJson
        @SuppressWarnings("unused")
        public Messages.SimpleRepeatedResponse produceJsonWithRepeatedFields() {
            return Messages.SimpleRepeatedResponse.newBuilder().addAllMessages(Collections.emptyList()).build();
        }
    }
}
