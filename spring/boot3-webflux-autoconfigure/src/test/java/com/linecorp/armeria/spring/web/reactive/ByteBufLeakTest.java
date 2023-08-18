/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.NetUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Timeout(10)
class ByteBufLeakTest {

    private static final AtomicInteger completed = new AtomicInteger();
    private static final Queue<NettyDataBuffer> allocatedBuffers = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean requestReceived = new AtomicBoolean();

    @SpringBootApplication
    static class TestConfiguration {
        @Bean
        public DataBufferFactory dataBufferFactory() {
            return new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT) {
                // This method will be called when emitting string from Mono/Flux.
                @Override
                public NettyDataBuffer allocateBuffer(int initialCapacity) {
                    final NettyDataBuffer buffer = super.allocateBuffer(initialCapacity);
                    // Keep allocated buffers.
                    allocatedBuffers.offer(buffer);
                    return buffer;
                }
            };
        }

        @RestController
        static class TestController {
            @GetMapping("/mono")
            Mono<String> mono() {
                addListenerForCountingCompletedRequests();
                return Mono.just("hello, WebFlux!");
            }

            @GetMapping("/flux")
            Flux<String> flux() {
                addListenerForCountingCompletedRequests();
                return Flux.just("a", "bc", "def", "hgij", "klmno")
                           .repeat(10)
                           .delayElements(Duration.ofMillis(10));
            }

            @GetMapping("/empty")
            Mono<String> empty() {
                addListenerForCountingCompletedRequests();
                return Mono.empty();
            }

            private static void addListenerForCountingCompletedRequests() {
                ServiceRequestContext.current().log().whenComplete()
                                     .thenAccept(log -> {
                                         completed.incrementAndGet();
                                         requestReceived.set(true);
                                     });
            }
        }
    }

    @LocalServerPort
    int port;

    @Test
    void confirmNoBufferLeak() throws Exception {
        assertThat(allocatedBuffers).isEmpty();
        final WebClient client = WebClient.of("http://127.0.0.1:" + port);
        for (int i = 0; i < 2; i++) {
            assertThat(client.get("/mono").aggregate().join().contentUtf8())
                    .isEqualTo("hello, WebFlux!");
            assertThat(client.get("/flux").aggregate().join().contentUtf8())
                    .isEqualTo("abcdefhgijklmnoabcdefhgijklmnoabcdefhgijklmnoabcdefhgijklmnoabcdefhgijklmno" +
                               "abcdefhgijklmnoabcdefhgijklmnoabcdefhgijklmnoabcdefhgijklmnoabcdefhgijklmno" +
                               "abcdefhgijklmno");
            assertThat(client.get("/empty").aggregate().join().contentUtf8())
                    .isEmpty();
        }

        ensureAllBuffersAreReleased();
    }

    @Test
    void confirmNoBufferLeak_resetConnection() throws Exception {
        completed.set(0);
        assertThat(allocatedBuffers).isEmpty();

        for (int i = 0; i < 2 * 3; i++) {
            try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
                requestReceived.set(false);
                s.setSoLinger(true, 0);
                final PrintWriter outWriter = new PrintWriter(s.getOutputStream(), false);
                switch (i % 3) {
                    case 0:
                        outWriter.print("GET /mono HTTP/1.1\r\n\r\n");
                        break;
                    case 1:
                        outWriter.print("GET /flux HTTP/1.1\r\n\r\n");
                        break;
                    case 2:
                        outWriter.print("GET /empty HTTP/1.1\r\n\r\n");
                        break;
                }
                outWriter.flush();

                await().untilTrue(requestReceived);
            }
        }

        // Wait until all request has been completed.
        await().until(() -> completed.get() == 2 * 3);

        ensureAllBuffersAreReleased();
    }

    private static void ensureAllBuffersAreReleased() {
        await().untilAsserted(() -> {
            NettyDataBuffer buffer;
            while ((buffer = allocatedBuffers.peek()) != null) {
                assertThat(buffer.getNativeBuffer().refCnt()).isZero();
                allocatedBuffers.poll();
            }
            assertThat(allocatedBuffers).isEmpty();
        });
    }
}
