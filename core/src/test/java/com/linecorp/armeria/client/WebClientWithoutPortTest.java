/*
 * Copyright 2024 LINE Corporation
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

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;

public class WebClientWithoutPortTest {
    @Test
    void defaultWebClient() {
        final WebClient defaultWebClient = WebClient.builder()
                                                    .decorator((delegate, ctx, req) -> {
                                                        final Endpoint endpoint = ctx.endpoint();
                                                        assertThat(endpoint.host()).isEqualTo("a.com");
                                                        assertThat(endpoint.hasPort()).isFalse();
                                                        return HttpResponse.of(200);
                                                    })
                                                    .build();
        assertThat(defaultWebClient.blocking().get("http://a.com:/").status().code()).isEqualTo(200);
    }

    @Test
    void buildedByUriStringWebClient() {
        final WebClient webClient = WebClient.builder("http://a.com:")
                                             .decorator((delegate, ctx, req) -> {
                                                 final Endpoint endpoint = ctx.endpoint();
                                                 assertThat(endpoint.host()).isEqualTo("a.com");
                                                 assertThat(endpoint.hasPort()).isFalse();
                                                 return HttpResponse.of(200);
                                             })
                                             .build();
        assertThat(webClient.blocking().get("/").status().code()).isEqualTo(200);
    }

    @Test
    void buildedByUriWebClient() throws URISyntaxException {
        final URI testUri = new URI("http://a.com:");
        final WebClient webClient = WebClient.builder(testUri)
                                             .decorator((delegate, ctx, req) -> {
                                                 final Endpoint endpoint = ctx.endpoint();
                                                 assertThat(endpoint.host()).isEqualTo("a.com");
                                                 assertThat(endpoint.hasPort()).isFalse();
                                                 return HttpResponse.of(200);
                                             })
                                             .build();
        assertThat(webClient.blocking().get("/").status().code()).isEqualTo(200);
    }
}
