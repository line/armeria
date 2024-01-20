/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactories;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.compression.Brotli;

class DecodingServiceTest {

    private static final int ORIGINAL_MESSAGE_LENGTH = 10000;

    static {
        // Initialize the Brotli native module.
        Brotli.isAvailable();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/decodeTest", (ctx1, req1) -> HttpResponse.of(
                    req1.aggregate()
                        .thenApply(aggregated -> {
                            return HttpResponse.of("Hello " + aggregated.contentUtf8() + '!');
                        })));

            sb.route()
              .path("/length-limit")
              .maxRequestLength(ORIGINAL_MESSAGE_LENGTH - 1)
              .build((ctx, req) -> {
                  return HttpResponse.of(req.aggregate().thenApply(agg -> {
                      // The large decoded content should be rejected by DecodingService.
                      return HttpResponse.of("Should never reach here");
                  }));
              });
            sb.decorator(DecodingService.newDecorator());
        }
    };

    @EnumSource(StreamEncoderFactories.class)
    @ParameterizedTest
    void decodingCompressedPayloadFromClient(StreamEncoderFactories factory) throws IOException {
        final WebClient client = WebClient.builder(server.httpUri()).build();
        final RequestHeaders headers =
                RequestHeaders.of(HttpMethod.POST, "/decodeTest",
                                  HttpHeaderNames.CONTENT_ENCODING, factory.encodingHeaderValue());
        final ByteBuf buf = Unpooled.buffer();
        final OutputStream encodingStream = factory.newEncoder(new ByteBufOutputStream(buf));

        final String testString = "Armeria " + factory.encodingHeaderValue() + " Test";
        final byte[] testByteArray = testString.getBytes(StandardCharsets.UTF_8);
        encodingStream.write(testByteArray);
        encodingStream.flush();

        assertThat(client.execute(headers, HttpData.wrap(ByteBufUtil.getBytes(buf))).aggregate().join()
                         .contentUtf8()).isEqualTo("Hello " + testString + '!');
        buf.release();
    }

    @ArgumentsSource(HighlyCompressedOutputStreamProvider.class)
    @ParameterizedTest
    void shouldLimitDecodedContentLength(String contentEncoding, byte[] compressed) {
        final BlockingWebClient client = server.blockingWebClient();
        final RequestHeaders headers = RequestHeaders.of(HttpMethod.POST, "/length-limit",
                                                         HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        final AggregatedHttpResponse response = client.execute(headers, HttpData.wrap(compressed));
        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
    }

    private static class HighlyCompressedOutputStreamProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final byte[] original = Strings.repeat("1", ORIGINAL_MESSAGE_LENGTH).getBytes();
            return Arrays.stream(StreamEncoderFactories.values()).map(factory -> {
                final ByteBuf buf = Unpooled.buffer();
                final OutputStream encodingStream = factory.newEncoder(new ByteBufOutputStream(buf));
                try {
                    encodingStream.write(original);
                    encodingStream.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                final byte[] compressed = ByteBufUtil.getBytes(buf);
                assertThat(compressed.length).isLessThan(original.length);
                buf.release();
                return Arguments.of(factory.encodingHeaderValue(), compressed);
            });
        }
    }
}
