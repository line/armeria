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

package com.linecorp.armeria.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.primitives.Bytes;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class StreamMessagesTest {

    @TempDir
    static Path tempDir;

    @Test
    void writeStream() throws IOException {
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
        final Path result = StreamMessages.writeTo(publisher, destination).join();
        assertThat(result).isSameAs(destination);
        final byte[] bytes = Files.readAllBytes(result);
        assertThat(bytes).contains(expected);

        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }

    @Test
    void invalidOpenOption() {
        assertThatThrownBy(() -> {
            StreamMessages.writeTo(StreamMessage.of(), tempDir.resolve("bar.txt"), StandardOpenOption.READ);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessage("READ not allowed");
    }

    @Test
    void invalidFile() {
        final Path invalidPath = Paths.get("/dev/null/foo/bar/qux/quz");

        final List<ByteBuf> bufs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bufs.add(Unpooled.wrappedBuffer(Integer.toString(i).getBytes()));
        }
        final HttpData[] httpData = bufs.stream().map(HttpData::wrap).toArray(HttpData[]::new);
        final CompletableFuture<Path> result =
                StreamMessages.writeTo(StreamMessage.of(httpData), invalidPath);
        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(FileSystemException.class);

        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }
}
