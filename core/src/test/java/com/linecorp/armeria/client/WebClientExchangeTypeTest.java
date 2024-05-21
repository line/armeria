/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.AbstractComparableAssert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebClientExchangeTypeTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    private static WebClient client;

    @BeforeAll
    static void beforeAll() {
        client = server.webClient();
    }

    @Test
    void simple() {
        assertExchangeType(() -> client.get("/").aggregate())
                .isEqualTo(ExchangeType.RESPONSE_STREAMING);
    }

    @Test
    void fixedMessage() {
        assertExchangeType(() -> {
            client.execute(HttpRequest.of(HttpMethod.POST, "/",
                                          MediaType.PLAIN_TEXT, "foo"))
                  .aggregate();
        }).isEqualTo(ExchangeType.RESPONSE_STREAMING);
    }

    @Test
    void fixedMessageWithCustomRequestOptions() {
        assertExchangeType(() -> {
            client.execute(HttpRequest.of(HttpMethod.POST, "/",
                                          MediaType.PLAIN_TEXT, "foo"),
                           RequestOptions.builder()
                                         .maxResponseLength(1000)
                                         .build())
                  .aggregate();
        }).isEqualTo(ExchangeType.RESPONSE_STREAMING);

        assertExchangeType(() -> {
            client.execute(HttpRequest.of(HttpMethod.POST, "/",
                                          MediaType.PLAIN_TEXT, "foo"),
                           RequestOptions.builder()
                                         .exchangeType(ExchangeType.BIDI_STREAMING)
                                         .build())
                  .aggregate();
        }).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    @Test
    void publisher() {
        assertExchangeType(() -> {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/"),
                                          StreamMessage.of(HttpData.ofUtf8("foo"))))
                  .aggregate();
        }).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    @EnumSource(ExchangeType.class)
    @ParameterizedTest
    void manual(ExchangeType exchangeType) {
        assertExchangeType(() -> {
            client.execute(HttpRequest.of(HttpMethod.POST, "/",
                                          MediaType.PLAIN_TEXT, "foo"),
                           RequestOptions.builder().exchangeType(exchangeType).build())
                  .aggregate();
        }).isEqualTo(exchangeType);
    }

    @Test
    void blocking() {
        assertExchangeType(() -> client.blocking().get("/"))
                .isEqualTo(ExchangeType.UNARY);
    }

    static AbstractComparableAssert<?, ExchangeType> assertExchangeType(Runnable action) {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            action.run();
            return assertThat(captor.get().exchangeType());
        }
    }
}
