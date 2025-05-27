/*
 * Copyright 2018 LINE Corporation
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

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;


/**
 * Makes sure Armeria HTTP client respects HTTP/2 flow control setting.
 */
public class HttpClientFlowControlTest {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientFlowControlTest.class);

    private static final String PATH = "/test";
    private static final int CONNECTION_WINDOW = 1024 * 1024; // 1MB connection window
    private static final int STREAM_WINDOW = CONNECTION_WINDOW / 2; // 512KB stream window

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(PATH, (ctx, req) -> {
                return HttpResponse.of(
                        ResponseHeaders.of(HttpStatus.OK),
                        HttpData.wrap(new byte[CONNECTION_WINDOW + 1025])); // a slightly larger than 1MB response
            });
        }
    };


    private static final ClientFactory clientFactory = ClientFactory.builder()
            .idleTimeoutMillis(0)
            .http2InitialStreamWindowSize(STREAM_WINDOW)
            .http2InitialConnectionWindowSize(CONNECTION_WINDOW)
            .build();

    @AfterEach
    void tearDown() {
        clientFactory.close();
    }


    @Test
    void flowControl() throws Exception {
        final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                .factory(clientFactory)
                .responseTimeoutMillis(120)
                .build();

        final DecodedHttpResponse response = (DecodedHttpResponse) client.get(PATH);

        // wait sometime for first stream to read response from server, until InboundTrafficController
        // kicks in
        await().until(() -> InboundTrafficController.numDeferredReads() > 0);
        InboundTrafficController controller = response.getInboundTrafficController();
        logger.info(controller.toString());

        // assert that the controller is indeed suspended
        assertThat(controller.isSuspended()).isTrue();
        //  unexpected 1: writtenBytes of the first response is larger than CONNECTION_WINDOW -
        //  it should be at most STREAM_WINDOW
        assertThat(response.writtenBytes()).isGreaterThan(CONNECTION_WINDOW);
        // unexpected 2: second stream on the same connection is blocked and even headers cannot
        // be read.
        assertThatThrownBy(() -> {
            client.get(PATH).split().headers().get(1000, TimeUnit.MILLISECONDS);
        })
        .isInstanceOf(java.util.concurrent.ExecutionException.class)
        .hasCauseInstanceOf(ResponseTimeoutException.class);
    }
}
