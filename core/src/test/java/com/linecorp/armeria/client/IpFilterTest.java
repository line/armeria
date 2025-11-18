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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.IpAddressRejectedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class IpFilterTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @Test
    void rejectInvalidIp() {
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .ipAddressFilter(ip -> {
                                      if ("1.2.3.4".equals(ip.getAddress().getHostAddress())) {
                                          return false;
                                      }
                                      return true;
                                  }).build()) {
            final BlockingWebClient client = WebClient.builder()
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThatThrownBy(() -> {
                client.get("http://1.2.3.4:" + server.httpPort() + "/foo");
            }).isInstanceOf(UnprocessedRequestException.class)
              .hasCauseInstanceOf(IpAddressRejectedException.class)
              .hasMessageContaining("Invalid IP address: /1.2.3.4:" + server.httpPort());

            final AggregatedHttpResponse res = client.get("http://127.0.0.1:" + server.httpPort() + "/foo");
            assertThat(res.contentUtf8()).isEqualTo("OK");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }
}
