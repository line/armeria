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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;

public class ResponseTimeoutTest {
    private static final Consumer<? super ChannelPipeline> CHANNEL_PIPELINE_CUSTOMIZER =
            pipeline ->  pipeline.addLast(new ChannelTrafficShapingHandler(1024, 0)); 

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final ResponseHeadersBuilder responseHeadersBuilder = ResponseHeaders.builder().status(200);
            responseHeadersBuilder.add("header1", Strings.repeat("a", 2048)); // set a header over 1KB;

            sb.channelPipelineCustomizer(CHANNEL_PIPELINE_CUSTOMIZER)
              .service(Route.ofCatchAll(), (ctx, req) -> HttpResponse.of(responseHeadersBuilder.build()));
        }
    };

    @Test
    void testResponseTimeout() {
        final RequestHeadersBuilder headersBuilder = RequestHeaders.builder(HttpMethod.GET, "/");

        // using h1c since http2 compresses headers
        assertThatThrownBy(() -> WebClient.builder(SessionProtocol.H1C, server.httpEndpoint())
                                          .responseTimeoutMillis(1000)
                                          .build()
                                          .blocking()
                                          .execute(headersBuilder.build(), "content"))
                .isInstanceOf(ResponseTimeoutException.class);
    }
}
