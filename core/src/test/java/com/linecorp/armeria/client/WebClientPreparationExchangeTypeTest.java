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

import static com.linecorp.armeria.client.WebClientExchangeTypeTest.assertExchangeType;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebClientPreparationExchangeTypeTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            sb.service("/json", (ctx, req) -> HttpResponse.ofJson("string"));
        }
    };

    @TempDir
    static Path tempDir;

    private static WebClient client;

    @BeforeAll
    static void beforeAll() {
        client = server.webClient();
    }

    @Test
    void fixedRequest() {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content("foo")
                  .execute()
                  .aggregate();
        }).isEqualTo(ExchangeType.RESPONSE_STREAMING);
    }

    @Test
    void publisherRequest() {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content(MediaType.PLAIN_TEXT, StreamMessage.of(HttpData.ofUtf8("foo")))
                  .execute()
                  .aggregate();
        }).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    @Test
    void responseString_default() {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content("foo")
                  .asString()
                  .execute();
        }).isEqualTo(ExchangeType.UNARY);
    }

    @Test
    void responseString_custom() {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content("foo")
                  .asString()
                  .exchangeType(ExchangeType.REQUEST_STREAMING)
                  .execute();
        }).isEqualTo(ExchangeType.REQUEST_STREAMING);
    }

    @Test
    void responseJson() {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/json")
                  .content("foo")
                  .asJson(String.class)
                  .execute();
        }).isEqualTo(ExchangeType.UNARY);
    }

    @Test
    void responseFile() {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content(MediaType.PLAIN_TEXT, StreamMessage.of(HttpData.ofUtf8("foo")))
                  .asFile(tempDir.resolve("/foo"))
                  .execute();
        }).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    @CsvSource({"true, UNARY", "false, RESPONSE_STREAMING"})
    @ParameterizedTest
    void responseAs(boolean requiresAggregation, ExchangeType exchangeType) {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content("foo")
                  .as(new ResponseAs<HttpResponse, String>() {
                          @Override
                          public String as(HttpResponse response) {
                              return response.aggregate().join().contentUtf8();
                          }

                          @Override
                          public boolean requiresAggregation() {
                              return requiresAggregation;
                          }
                      }
                  )
                  .execute();
        }).isEqualTo(exchangeType);
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void responseAs_custom(boolean requiresAggregation) {
        assertExchangeType(() -> {
            client.prepare()
                  .post("/")
                  .content("foo")
                  .as(new ResponseAs<HttpResponse, String>() {
                          @Override
                          public String as(HttpResponse response) {
                              return response.aggregate().join().contentUtf8();
                          }

                          @Override
                          public boolean requiresAggregation() {
                              return requiresAggregation;
                          }
                      }
                  )
                  .exchangeType(ExchangeType.BIDI_STREAMING)
                  .execute();
        }).isEqualTo(ExchangeType.BIDI_STREAMING);
    }

    @Test
    void blocking_default() {
        assertExchangeType(() -> {
            client.blocking()
                  .prepare()
                  .post("/")
                  .content(MediaType.PLAIN_TEXT, StreamMessage.of(HttpData.ofUtf8("foo")))
                  .execute();
        }).isEqualTo(ExchangeType.UNARY);
    }

    @Test
    void blocking_custom() {
        assertExchangeType(() -> {
            client.blocking()
                  .prepare()
                  .post("/")
                  .content(MediaType.PLAIN_TEXT, StreamMessage.of(HttpData.ofUtf8("foo")))
                  .exchangeType(ExchangeType.RESPONSE_STREAMING)
                  .execute();
        }).isEqualTo(ExchangeType.RESPONSE_STREAMING);
    }
}
