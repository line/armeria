/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ByteBufsInputStreamTest {

    @Test
    void testScanner() {
        final List<String> strings = ImmutableList.of("first,", "second,", "third,fourth", ",fifth");
        final Scanner scanner = new Scanner(String.join("", strings)).useDelimiter(",");
        final ImmutableList.Builder<String> resultBuilder = ImmutableList.builder();
        while (scanner.hasNext()) {
            final String s = scanner.next();
            resultBuilder.add(s);
        }
        final List<String> resultStrings = resultBuilder.build();
        assertThat(resultStrings.size()).isEqualTo(5);
        assertThat(String.join(",", resultStrings)).isEqualTo(String.join("", strings));
        assertThat(resultStrings.get(0)).isEqualTo("first");
        assertThat(resultStrings.get(1)).isEqualTo("second");
        assertThat(resultStrings.get(2)).isEqualTo("third");
        assertThat(resultStrings.get(3)).isEqualTo("fourth");
        assertThat(resultStrings.get(4)).isEqualTo("fifth");
    }

    @Test
    void testPreBuffered() {
        final List<String> strings = ImmutableList.of("first,", "second,", "third,fourth", ",fifth");

        final ByteBufsInputStream stream = new ByteBufsInputStream();
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);
        strings.forEach(s -> stream.add(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8))));
        stream.setEos();

        final Scanner scanner = new Scanner(stream).useDelimiter(",");
        final ImmutableList.Builder<String> resultBuilder = ImmutableList.builder();
        while (scanner.hasNext()) {
            final String s = scanner.next();
            resultBuilder.add(s);
        }

        final List<String> resultStrings = resultBuilder.build();

        assertThat(stream.isEos()).isTrue();
        assertThat(stream.available()).isEqualTo(0);

        assertThat(resultStrings.size()).isEqualTo(5);
        assertThat(String.join(",", resultStrings)).isEqualTo(String.join("", strings));
        assertThat(resultStrings.get(0)).isEqualTo("first");
        assertThat(resultStrings.get(1)).isEqualTo("second");
        assertThat(resultStrings.get(2)).isEqualTo("third");
        assertThat(resultStrings.get(3)).isEqualTo("fourth");
        assertThat(resultStrings.get(4)).isEqualTo("fifth");
    }

    @Test
    void testAdd_ignoreEmptyByteBuf() {
        final List<String> strings1 = ImmutableList.of("first,", "second,");
        final List<String> strings2 = ImmutableList.of("third,fourth", ",fifth");
        final ByteBuf empty = Unpooled.buffer();

        final ByteBufsInputStream stream = new ByteBufsInputStream();
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);
        strings1.forEach(s -> stream.add(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8))));
        stream.add(empty);
        strings2.forEach(s -> stream.add(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8))));
        stream.setEos();

        final Scanner scanner = new Scanner(stream).useDelimiter(",");
        final ImmutableList.Builder<String> resultBuilder = ImmutableList.builder();
        while (scanner.hasNext()) {
            final String s = scanner.next();
            resultBuilder.add(s);
        }

        final List<String> resultStrings = resultBuilder.build();

        assertThat(stream.isEos()).isTrue();
        assertThat(stream.available()).isEqualTo(0);

        assertThat(resultStrings.size()).isEqualTo(5);
        final List<String> strings = Stream
                .concat(strings1.stream(), strings2.stream())
                .collect(Collectors.toList());
        assertThat(String.join(",", resultStrings)).isEqualTo(String.join("", strings));
        assertThat(resultStrings.get(0)).isEqualTo("first");
        assertThat(resultStrings.get(1)).isEqualTo("second");
        assertThat(resultStrings.get(2)).isEqualTo("third");
        assertThat(resultStrings.get(3)).isEqualTo("fourth");
        assertThat(resultStrings.get(4)).isEqualTo("fifth");
    }

    @Test
    void testEof() throws Exception {
        final ByteBufsInputStream stream = new ByteBufsInputStream();
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);

        stream.setEos();
        assertThat(stream.isEos()).isTrue();
        assertThat(stream.available()).isEqualTo(0);
        assertThat(stream.read()).isEqualTo(-1);

        assertThatThrownBy(() -> stream.add(Unpooled.wrappedBuffer(new byte[] {})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already closed");
    }

    @Test
    void testEofAsync() throws Exception {
        final ByteBufsInputStream stream = new ByteBufsInputStream();
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);

        final CompletableFuture<String> consumer = CompletableFuture.supplyAsync(() -> {
            try {
                return new BufferedReader(new InputStreamReader(stream)).readLine();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });

        CompletableFuture.runAsync(stream::setEos);

        final String s = consumer.get();
        assertThat(s).isNull();
        assertThat(stream.isEos()).isTrue();
        assertThat(stream.available()).isEqualTo(0);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void testTimeout() throws Exception {
        final ByteBufsInputStream stream = new ByteBufsInputStream(Duration.ofMillis(100L));
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);

        final CompletableFuture<String> consumer = CompletableFuture.supplyAsync(() -> {
            try {
                return new BufferedReader(new InputStreamReader(stream)).readLine();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });

        assertThatThrownBy(consumer::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseExactlyInstanceOf(InterruptedByTimeoutException.class);
    }

    @Test
    void testInterrupt() throws Exception {
        final ByteBufsInputStream stream = new ByteBufsInputStream();
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);

        final CompletableFuture<String> consumer = CompletableFuture.supplyAsync(() -> {
            try {
                return new BufferedReader(new InputStreamReader(stream)).readLine();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });

        Thread.sleep(100L);
        stream.interrupt(new IllegalStateException("my fault"));

        assertThatThrownBy(consumer::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseExactlyInstanceOf(InterruptedIOException.class)
                .cause().hasCauseExactlyInstanceOf(IllegalStateException.class)
                .cause().hasMessageContaining("my fault");
    }

    @ParameterizedTest
    @MethodSource("parametersForBufferedAsync")
    void testBufferedAsync(List<String> strings) throws Exception {
        final ByteBufsInputStream stream = new ByteBufsInputStream();
        assertThat(stream.isEos()).isFalse();
        assertThat(stream.available()).isEqualTo(0);

        final CompletableFuture<List<String>> consumer = CompletableFuture.supplyAsync(() -> {
            final Scanner scanner = new Scanner(stream).useDelimiter(",");
            final ImmutableList.Builder<String> resultBuilder = ImmutableList.builder();
            while (scanner.hasNext()) {
                final String s = scanner.next();
                resultBuilder.add(s);
            }
            return resultBuilder.build();
        });

        CompletableFuture.runAsync(() -> {
            strings.forEach(s -> stream.add(Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.UTF_8))));
            stream.setEos();
        });

        final List<String> resultStrings = consumer.get();

        assertThat(stream.isEos()).isTrue();
        assertThat(stream.available()).isEqualTo(0);

        assertThat(resultStrings.size()).isEqualTo(5);
        assertThat(String.join(",", resultStrings)).isEqualTo(String.join("", strings));
        assertThat(resultStrings.get(0)).isEqualTo("first");
        assertThat(resultStrings.get(1)).isEqualTo("second");
        assertThat(resultStrings.get(2)).isEqualTo("third");
        assertThat(resultStrings.get(3)).isEqualTo("fourth");
        assertThat(resultStrings.get(4)).isEqualTo("fifth");
    }

    private static Stream<Arguments> parametersForBufferedAsync() {
        return Stream.of(Arguments.of(ImmutableList.of("first,", "second,", "third,fourth", ",fifth")),
                         Arguments.of(ImmutableList.of(
                                 "fir", "st,", "secon", "d", ",third,fou", "rth", ",", "fif", "th"))
        );
    }
}
