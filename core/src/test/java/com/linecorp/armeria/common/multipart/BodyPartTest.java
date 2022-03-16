/*
 * Copyright 2021 LINE Corporation
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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Flux;

/**
 * Tests {@link BodyPart}.
 */
class BodyPartTest {

    @TempDir
    static Path tempDir;

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/test/java/io/helidon/media/multipart/BodyPartTest.java

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    @Test
    void testDefaultContentType() {
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(HttpHeaders.of())
                                          .content("Hello")
                                          .build();
        final HttpHeaders headers = bodyPart.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.PLAIN_TEXT);
    }

    @Test
    void testDefaultContentTypeForFile() {
        final BodyPart bodyPart =
                BodyPart.builder()
                        .headers(HttpHeaders.builder()
                                            .set("Content-Disposition", "form-data; filename=foo")
                                            .build())
                        .content("Hello")
                        .build();
        final HttpHeaders headers = bodyPart.headers();
        assertThat(headers.contentType()).isNotNull();
        assertThat(headers.contentType()).isEqualTo(MediaType.OCTET_STREAM);
    }

    @Test
    void testContentFromPublisher() {
        final BodyPart bodyPart =
                BodyPart.builder()
                        .content(StreamMessage.of(HttpData.of(DEFAULT_CHARSET, "body part data")))
                        .build();
        assertThat(getContents(bodyPart)).containsExactly("body part data");
    }

    private static List<String> getContents(BodyPart bodyPart) {
        return Flux.from(bodyPart.content()).map(HttpData::toStringUtf8).collectList().block();
    }

    @Test
    void testContentFromEntity() throws Exception {
        final BodyPart bodyPart = BodyPart.builder()
                                          .content("body part data")
                                          .build();
        assertThat(getContents(bodyPart)).containsExactly("body part data");
    }

    @Test
    void testBuildingPartWithNoContent() {
        assertThatThrownBy(() -> BodyPart.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one content");
    }

    @Test
    void testName() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentDisposition(ContentDisposition.builder("form-data")
                                                                                     .name("foo")
                                                                                     .build())
                                               .build();
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(headers)
                                          .content("abc")
                                          .build();

        assertThat(bodyPart.headers().contentDisposition().type()).isEqualTo("form-data");
        assertThat(bodyPart.name()).isEqualTo("foo");
        assertThat(bodyPart.filename()).isNull();
    }

    @Test
    void testFilename() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .contentDisposition(ContentDisposition
                                                                           .builder("attachment")
                                                                           .filename("foo.txt")
                                                                           .build())
                                               .build();
        final BodyPart bodyPart = BodyPart.builder()
                                          .headers(headers)
                                          .content("abc")
                                          .build();

        assertThat(bodyPart.filename()).isEqualTo("foo.txt");
        assertThat(bodyPart.name()).isNull();
    }

    @Test
    void testWriteFile() throws IOException {
        final List<ByteBuf> bufs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bufs.add(Unpooled.wrappedBuffer(Integer.toString(i).getBytes()));
        }
        final HttpData[] httpData = bufs.stream().map(HttpData::wrap).toArray(HttpData[]::new);
        final byte[] expected = Arrays.stream(httpData)
                                      .map(HttpData::array)
                                      .reduce(Bytes::concat).get();

        final StreamMessage<HttpData> publisher = StreamMessage.of(httpData);
        final Path destination = tempDir.resolve("foo.bin");
        BodyPart.of(HttpHeaders.of(), publisher)
                .writeTo(destination,
                         CommonPools.workerGroup().next(),
                         CommonPools.blockingTaskExecutor())
                .join();
        final byte[] bytes = Files.readAllBytes(destination);
        assertThat(bytes).contains(expected);

        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }

    @Test
    void testAggregate() {
        final List<ByteBuf> bufs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bufs.add(Unpooled.wrappedBuffer(Integer.toString(i).getBytes()));
        }
        final HttpData[] httpData = bufs.stream().map(HttpData::wrap).toArray(HttpData[]::new);
        final byte[] expected = Arrays.stream(httpData)
                                      .map(HttpData::array)
                                      .reduce(Bytes::concat).get();

        final StreamMessage<HttpData> publisher = StreamMessage.of(httpData);
        final AggregatedBodyPart result = BodyPart.of(HttpHeaders.of(), publisher)
                                                  .aggregate()
                                                  .join();
        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }

        try (HttpData content = result.content()) {
            assertThat(content.array()).contains(expected);
            assertThat(content.isPooled()).isFalse();
        }
    }

    @Test
    void testAggregateWithPooledObjects() {
        final List<ByteBuf> bufs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bufs.add(Unpooled.wrappedBuffer(Integer.toString(i).getBytes()));
        }
        final HttpData[] httpData = bufs.stream().map(HttpData::wrap).toArray(HttpData[]::new);
        final byte[] expected = Arrays.stream(httpData)
                                      .map(HttpData::array)
                                      .reduce(Bytes::concat).get();

        final StreamMessage<HttpData> publisher = StreamMessage.of(httpData);
        final AggregatedBodyPart result = BodyPart.of(HttpHeaders.of(), publisher)
                                                  .aggregateWithPooledObjects(ByteBufAllocator.DEFAULT)
                                                  .join();
        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
        try (HttpData content = result.content()) {
            assertThat(content.array()).contains(expected);
            assertThat(content.isPooled()).isTrue();
        }
    }
}
