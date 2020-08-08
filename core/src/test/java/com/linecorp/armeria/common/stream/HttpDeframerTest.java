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

package com.linecorp.armeria.common.stream;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.concurrent.ImmediateEventExecutor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class HttpDeframerTest {

    @Test
    void insufficientData() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final Flux<HttpData> stream = Flux.just(HttpData.ofUtf8("A012345"));
        stream.subscribe(decoder);
        StepVerifier.create(decoder)
                    .expectComplete()
                    .verify();
    }

    @Test
    void truncatedData() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final Flux<HttpData> stream = Flux.just("A012345", "6789B1234")
                                          .map(HttpData::ofUtf8);
        stream.subscribe(decoder);
        StepVerifier.create(decoder)
                    .expectNext("A0123456789")
                    .expectComplete()
                    .verify();
    }

    @Test
    void uniformData() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final Flux<HttpData> stream = Flux.just("A0123456789",
                                                "B0123456789",
                                                "C0123456789",
                                                "D0123456789",
                                                "E0123456789")
                                          .map(HttpData::ofUtf8);
        stream.subscribe(decoder);
        StepVerifier.create(decoder)
                    .expectNext("A0123456789")
                    .expectNext("B0123456789")
                    .expectNext("C0123456789")
                    .expectNext("D0123456789")
                    .expectNext("E0123456789")
                    .expectComplete()
                    .verify();
    }

    @Test
    void withEmptyData() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final Flux<HttpData> stream = Flux.just(HttpData.empty(), HttpData.ofUtf8("A0123456"),
                                                HttpData.empty(), HttpData.ofUtf8("789B"));
        stream.subscribe(decoder);
        StepVerifier.create(decoder)
                    .expectNext("A0123456789")
                    .expectComplete()
                    .verify();
    }

    @Test
    void scatteredData() {
        final FixedLengthDecoder decoder = new FixedLengthDecoder(11);
        final Flux<HttpData> stream = Flux.just("A012345",
                                                "6789B0",
                                                "12",
                                                "",
                                                "3",
                                                "456789",
                                                "C01234",
                                                "56789D",
                                                "0123456789E0123456789")
                                          .map(HttpData::ofUtf8);
        stream.subscribe(decoder);
        StepVerifier.create(decoder)
                    .expectNext("A0123456789")
                    .expectNext("B0123456789")
                    .expectNext("C0123456789")
                    .expectNext("D0123456789")
                    .expectNext("E0123456789")
                    .expectComplete()
                    .verify();
    }

    @Test
    void headerAwareData() {
        final Flux<HttpHeaders> headers = Flux.just(ResponseHeaders.of(HttpStatus.CONTINUE),
                                                    HttpHeaders.of(HttpHeaderNames.CONTENT_LENGTH, 11));
        final Flux<HttpData> stream = Flux.just("A012345",
                                                "6789B0",
                                                "12",
                                                "",
                                                "3",
                                                "456789",
                                                "C01234",
                                                "56789D",
                                                "0123456789E0123456789")
                                          .map(HttpData::ofUtf8);

        final HeaderAwareDecoder decoder = new HeaderAwareDecoder();

        Flux.concat(headers, stream).subscribe(decoder);
        StepVerifier.create(decoder)
                    .expectNext("A0123456789")
                    .expectNext("B0123456789")
                    .expectNext("C0123456789")
                    .expectNext("D0123456789")
                    .expectNext("E0123456789")
                    .expectComplete()
                    .verify();
    }

    private static final class FixedLengthDecoder extends HttpDeframer<String> {

        private final int length;

        private FixedLengthDecoder(int length) {
            super(ImmediateEventExecutor.INSTANCE, UnpooledByteBufAllocator.DEFAULT);
            this.length = length;
        }

        @Override
        protected void process(HttpDeframerInput in, HttpDeframerOutput<String> out) {
            int remained = in.readableBytes();
            if (remained < length) {
                return;
            }

            while (remained >= length) {
                final ByteBuf buf = in.readBytes(length);
                out.add(buf.toString(StandardCharsets.UTF_8));
                buf.release();
                remained -= length;
            }
        }
    }

    private static final class HeaderAwareDecoder extends HttpDeframer<String> {

        private int length;

        private HeaderAwareDecoder() {
            super(ImmediateEventExecutor.INSTANCE, UnpooledByteBufAllocator.DEFAULT);
        }

        @Override
        protected void processHeaders(HttpHeaders in, HttpDeframerOutput<String> out) {
            length = in.getInt(HttpHeaderNames.CONTENT_LENGTH);
        }

        @Override
        protected void process(HttpDeframerInput in, HttpDeframerOutput<String> out) {
            int remained = in.readableBytes();
            if (remained < length) {
                return;
            }

            while (remained >= length) {
                final ByteBuf buf = in.readBytes(length);
                out.add(buf.toString(StandardCharsets.UTF_8));
                buf.release();
                remained -= length;
            }
        }
    }
}
