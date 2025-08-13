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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;

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
                        HttpData.wrap(
                                new byte[CONNECTION_WINDOW + 1025])); // a slightly larger than 1MB response
            });
        }
    };

    @Test
    void flowControl() throws Exception {
        try (ClientFactory clientFactory = ClientFactory.builder()
                .http2InitialStreamWindowSize(STREAM_WINDOW)
                .http2InitialConnectionWindowSize(CONNECTION_WINDOW)
                .build()) {
            final WebClient client = WebClient.builder(server.uri(SessionProtocol.H2C))
                    .factory(clientFactory)
                    .build();

            final DecodedHttpResponse res1 = (DecodedHttpResponse) client.get(PATH);

            // wait sometime for first stream to read response from server, until InboundTrafficController
            // kicks in
            await().untilAsserted(() -> {
                assertThat(res1.writtenBytes()).isEqualTo(STREAM_WINDOW);
            });
            final InboundTrafficController controller = res1.inboundTrafficController();
            final Http2ConnectionDecoder decoder = controller.decoder();
            final Http2LocalFlowController flowController = decoder.flowController();
            final Http2Connection connection = decoder.connection();
            final Http2Stream stream3 = connection.stream(3);
            // Used up the flow control window for the first response
            assertThat(flowController.windowSize(stream3)).isZero();
            // assert that the controller is not suspended by the first response
            assertThat(controller.isSuspended()).isFalse();

            // The first stream should not interfere with the second stream.
            final DecodedHttpResponse res2 = (DecodedHttpResponse) client.get(PATH);
            await().untilAsserted(() -> {
                assertThat(res2.writtenBytes()).isEqualTo(STREAM_WINDOW);
            });

            final Http2Stream stream5 = connection.stream(5);
            assertThat(flowController.windowSize(stream5)).isZero();

            assertThat(flowController.windowSize(connection.connectionStream())).isEqualTo(0);
            assertThat(controller.isSuspended()).isFalse();

            logger.debug("Start aggregating the first response to release the flow control window.");
            final AggregatedHttpResponse aggRes1 = res1.aggregate().join();
            assertThat(aggRes1.status()).isEqualTo(HttpStatus.OK);
            assertThat(aggRes1.content().length()).isEqualTo(CONNECTION_WINDOW + 1025);

            final AggregatedHttpResponse aggRes2 = res2.aggregate().join();
            assertThat(aggRes2.status()).isEqualTo(HttpStatus.OK);
            assertThat(aggRes2.content().length()).isEqualTo(CONNECTION_WINDOW + 1025);

            assertThat(flowController.windowSize(connection.connectionStream())).isGreaterThan(0);
        }
    }

    @Test
    void testStreamWindowUpdateRatio() {
        assertThatThrownBy(() -> ClientFactory.builder().http2StreamWindowUpdateRatio(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("http2StreamWindowUpdateRatio: 0.0 (expected: > 0 and < 1.0)");

        assertThatThrownBy(() -> ClientFactory.builder().http2StreamWindowUpdateRatio(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("http2StreamWindowUpdateRatio: 1.0 (expected: > 0 and < 1.0)");

        assertThatCode(() -> {
            ClientFactory.builder().http2StreamWindowUpdateRatio(0.5f);
        }).doesNotThrowAnyException();
    }
}
