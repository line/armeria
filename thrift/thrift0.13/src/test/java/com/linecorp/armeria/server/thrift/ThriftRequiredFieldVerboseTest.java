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
package com.linecorp.armeria.server.thrift;

import static com.linecorp.armeria.common.thrift.ThriftSerializationFormats.TEXT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloRequiredNameService;

class ThriftRequiredFieldVerboseTest {

    private static final HelloRequiredNameService.Iface HELLO_SERVICE =
            name -> "Hello, " + name.getFirst() + '!';

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.verboseResponses(true)
              .service("/hello", THttpService.ofFormats(HELLO_SERVICE, TEXT))
              .route().verboseResponses(false)
              .path("/hello_no_verbose")
              .build(THttpService.ofFormats(HELLO_SERVICE, TEXT));
        }
    };

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void containRequiredFiledIfVerbose(boolean verbose) throws Exception {
        final String path = verbose ? "/hello" : "/hello_no_verbose";
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, path,
                                                         HttpHeaderNames.CONTENT_TYPE, "application/x-thrift");
        final String body = '{' +
                            "  \"method\": \"hello\"," +
                            "  \"type\":\"CALL\"," +
                            "  \"args\": {\"name\": {}}" +
                            '}';
        final AggregatedHttpResponse response = server.blockingWebClient()
                                                      .execute(HttpRequest.of(headers, HttpData.ofUtf8(body)));
        if (verbose) {
            assertThat(response.contentUtf8()).contains("Required field", "was not present");
        } else {
            assertThat(response.contentUtf8()).doesNotContain("Required field", "was not present");
        }
    }
}
