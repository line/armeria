/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.server.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Makes sure the pooled buffer of a {@link FileService} cache entry outlives the responses that are still
 * serving it, and is released once they are done.
 */
class FileServiceCacheRaceTest {

    private static final int CONCURRENCY = 32;

    // Large enough for the aggregation of concurrent requests to overlap, but small enough to stay under
    // the default 'maxCacheEntrySizeBytes' (64KiB) so that the files are cached at all.
    private static final String FOO_CONTENT = Strings.repeat("foo0123456789abcdef\n", 3000);
    private static final String BAR_CONTENT = Strings.repeat("bar0123456789abcdef\n", 3000);

    @TempDir
    Path tmpDir;

    @BeforeEach
    void createFiles() throws IOException {
        Files.write(tmpDir.resolve("foo.html"), FOO_CONTENT.getBytes(UTF_8));
        Files.write(tmpDir.resolve("bar.html"), BAR_CONTENT.getBytes(UTF_8));
    }

    /**
     * When many requests hit the same path while the entry cache is still empty, they all miss the cache and
     * each of them stores its own aggregated file. Every store but the last one is evicted as a replacement,
     * and an evicted buffer must not be released while a response is still about to read it.
     */
    @RepeatedTest(4)
    void concurrentColdCacheMissesAreServedIntact() {
        // A new server means an empty entry cache.
        try (TestServer server = TestServer.of(FileService.of(tmpDir))) {
            assertAllServedIntact(server.client(), "/foo.html", FOO_CONTENT);
        }
    }

    /**
     * The same lifetime problem, reached through size-based eviction rather than a replacement: an entry is
     * evicted as soon as the other path is cached.
     */
    @RepeatedTest(4)
    void concurrentRequestsAreServedIntactWhileTheCacheIsChurning() {
        // 'maximumSize=1' makes a request for one path evict the entry the other path is serving.
        try (TestServer server = TestServer.of(FileService.builder(tmpDir)
                                                          .entryCacheSpec("maximumSize=1")
                                                          .build())) {
            final WebClient client = server.client();
            final List<CompletableFuture<AggregatedHttpResponse>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(client.get(i % 2 == 0 ? "/foo.html" : "/bar.html").aggregate());
            }
            for (int i = 0; i < futures.size(); i++) {
                assertServedIntact(futures.get(i).join(), i % 2 == 0 ? FOO_CONTENT : BAR_CONTENT);
            }
        }
    }

    /**
     * A file whose attributes changed on disk makes the cached entry out of date, so it is invalidated and
     * re-cached while the responses that were serving it are still in flight.
     */
    @Test
    void concurrentRequestsAreServedIntactWhileTheCachedFileIsUpdated() throws IOException {
        final RecordingHttpVfs vfs = new RecordingHttpVfs(HttpVfs.of(tmpDir));
        try (TestServer server = TestServer.of(FileService.builder(vfs).build())) {
            final WebClient client = server.client();
            assertServedIntact(client.get("/foo.html").aggregate().join(), FOO_CONTENT);
            final ByteBuf oldBuf = vfs.aggregatedBufs.get(0);

            final BlockingPoint blockingPoint = vfs.blockNextToHttpFile();
            final CompletableFuture<AggregatedHttpResponse> oldResponse =
                    client.get("/foo.html").aggregate();
            blockingPoint.awaitEntered();
            try {
                final Path fooPath = tmpDir.resolve("foo.html");
                final long oldLastModifiedMillis = Files.getLastModifiedTime(fooPath).toMillis();
                Files.write(fooPath, BAR_CONTENT.getBytes(UTF_8));
                Files.setLastModifiedTime(fooPath, FileTime.fromMillis(oldLastModifiedMillis + 5000));

                assertServedIntact(client.get("/foo.html").aggregate().join(), BAR_CONTENT);
                // Invalidation released the cache ownership, but oldResponse still owns the old content.
                assertThat(oldBuf.refCnt()).isPositive();
            } finally {
                blockingPoint.resume();
            }
            assertServedIntact(oldResponse.join(), FOO_CONTENT);
            await().untilAsserted(() -> assertThat(oldBuf.refCnt()).isZero());
        }
    }

    /**
     * Keeping an evicted entry alive must not turn into a leak: once nothing is serving it any more, its
     * pooled buffer has to be released.
     */
    @Test
    void evictedCacheEntryIsReleased() {
        final RecordingHttpVfs vfs = new RecordingHttpVfs(HttpVfs.of(tmpDir));
        try (TestServer server = TestServer.of(FileService.builder(vfs)
                                                          .entryCacheSpec("maximumSize=1")
                                                          .build())) {
            final WebClient client = server.client();
            assertThat(client.get("/foo.html").aggregate().join().contentUtf8()).isEqualTo(FOO_CONTENT);
            assertThat(vfs.aggregatedBufs).hasSize(1);
            final ByteBuf fooBuf = vfs.aggregatedBufs.get(0);
            // The cache owns the buffer as long as the entry is mapped.
            assertThat(fooBuf.refCnt()).isPositive();

            // Evict 'foo.html' by caching 'bar.html'.
            assertThat(client.get("/bar.html").aggregate().join().contentUtf8()).isEqualTo(BAR_CONTENT);
            await().untilAsserted(() -> assertThat(fooBuf.refCnt()).isZero());
        }
    }

    private static void assertServedIntact(AggregatedHttpResponse res, String expectedContent) {
        assertThat(res.status()).withFailMessage("%s%n%s", res.status(), res.contentUtf8())
                                .isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(expectedContent);
    }

    private static void assertAllServedIntact(WebClient client, String path, String expectedContent) {
        final List<CompletableFuture<AggregatedHttpResponse>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            futures.add(client.get(path).aggregate());
        }
        for (CompletableFuture<AggregatedHttpResponse> future : futures) {
            assertServedIntact(future.join(), expectedContent);
        }
    }

    /**
     * Remembers the pooled buffer of every file it aggregated, so that a test can tell whether the buffer
     * was released.
     */
    private static final class RecordingHttpVfs implements HttpVfs {

        private final HttpVfs delegate;
        final List<ByteBuf> aggregatedBufs = new CopyOnWriteArrayList<>();
        private final AtomicReference<BlockingPoint> nextToHttpFile = new AtomicReference<>();

        RecordingHttpVfs(HttpVfs delegate) {
            this.delegate = delegate;
        }

        BlockingPoint blockNextToHttpFile() {
            final BlockingPoint blockingPoint = new BlockingPoint();
            if (!nextToHttpFile.compareAndSet(null, blockingPoint)) {
                throw new IllegalStateException("a blocking point is already installed");
            }
            return blockingPoint;
        }

        @Deprecated
        @Override
        public HttpFile get(Executor fileReadExecutor, String path, Clock clock,
                            @Nullable String contentEncoding, HttpHeaders additionalHeaders) {
            return new RecordingHttpFile(
                    delegate.get(fileReadExecutor, path, clock, contentEncoding, additionalHeaders));
        }

        @Override
        public HttpFile get(Executor fileReadExecutor, String path, Clock clock,
                            @Nullable String contentEncoding, HttpHeaders additionalHeaders,
                            MediaTypeResolver mediaTypeResolver) {
            return new RecordingHttpFile(delegate.get(fileReadExecutor, path, clock, contentEncoding,
                                                      additionalHeaders, mediaTypeResolver));
        }

        @Override
        public CompletableFuture<Boolean> canList(Executor fileReadExecutor, String path) {
            return delegate.canList(fileReadExecutor, path);
        }

        @Override
        public CompletableFuture<List<String>> list(Executor fileReadExecutor, String path) {
            return delegate.list(fileReadExecutor, path);
        }

        @Override
        public String meterTag() {
            return delegate.meterTag();
        }

        private final class RecordingHttpFile implements HttpFile {

            private final HttpFile delegate;

            RecordingHttpFile(HttpFile delegate) {
                this.delegate = delegate;
            }

            @Override
            public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
                return delegate.readAttributes(fileReadExecutor);
            }

            @Override
            public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
                return delegate.readHeaders(fileReadExecutor);
            }

            @Override
            public CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
                return delegate.read(fileReadExecutor, alloc);
            }

            @Override
            public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
                return delegate.aggregate(fileReadExecutor);
            }

            @Override
            public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(
                    Executor fileReadExecutor, ByteBufAllocator alloc) {
                return delegate.aggregateWithPooledObjects(fileReadExecutor, alloc).thenApply(aggregated -> {
                    final HttpData content = aggregated.content();
                    if (content != null && content.isPooled()) {
                        // A duplicate shares the reference count with the original buffer.
                        aggregatedBufs.add(content.byteBuf(ByteBufAccessMode.DUPLICATE));
                    }
                    return new BlockingAggregatedHttpFile(aggregated);
                });
            }

            @Override
            public HttpService asService() {
                return delegate.asService();
            }
        }

        private final class BlockingAggregatedHttpFile implements AggregatedHttpFile {

            private final AggregatedHttpFile delegate;

            BlockingAggregatedHttpFile(AggregatedHttpFile delegate) {
                this.delegate = delegate;
            }

            @Override
            public @Nullable HttpFileAttributes attributes() {
                return delegate.attributes();
            }

            @Override
            public @Nullable ResponseHeaders headers() {
                return delegate.headers();
            }

            @Override
            public @Nullable HttpData content() {
                return delegate.content();
            }

            @Override
            public HttpFile toHttpFile() {
                final BlockingPoint blockingPoint = nextToHttpFile.getAndSet(null);
                final HttpFile file = delegate.toHttpFile();
                if (blockingPoint == null) {
                    return file;
                }
                return new BlockingHttpFile(file, blockingPoint);
            }
        }

        private static final class BlockingHttpFile implements HttpFile {

            private final HttpFile delegate;
            private final BlockingPoint blockingPoint;

            BlockingHttpFile(HttpFile delegate, BlockingPoint blockingPoint) {
                this.delegate = delegate;
                this.blockingPoint = blockingPoint;
            }

            @Override
            public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
                return delegate.readAttributes(fileReadExecutor);
            }

            @Override
            public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
                return delegate.readHeaders(fileReadExecutor);
            }

            @Override
            public CompletableFuture<HttpResponse> read(Executor fileReadExecutor, ByteBufAllocator alloc) {
                blockingPoint.enter();
                return blockingPoint.resumed.thenCompose(unused -> delegate.read(fileReadExecutor, alloc));
            }

            @Override
            public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
                return delegate.aggregate(fileReadExecutor);
            }

            @Override
            public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(
                    Executor fileReadExecutor, ByteBufAllocator alloc) {
                return delegate.aggregateWithPooledObjects(fileReadExecutor, alloc);
            }

            @Override
            public HttpService asService() {
                return (ctx, req) -> HttpResponse.of(read(ctx.blockingTaskExecutor(), ctx.alloc()));
            }
        }
    }

    private static final class BlockingPoint {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CompletableFuture<Void> resumed = new CompletableFuture<>();

        void enter() {
            entered.countDown();
        }

        void awaitEntered() {
            await().untilAsserted(() -> assertThat(entered.getCount()).isZero());
        }

        void resume() {
            resumed.complete(null);
        }
    }

    /**
     * A server with a {@link FileService} whose entry cache starts out empty.
     */
    private static final class TestServer implements AutoCloseable {

        static TestServer of(FileService service) {
            final Server server = Server.builder()
                                        .serviceUnder("/", service)
                                        // Let a 500 carry the server-side stack trace, so that a failure
                                        // says which exception broke the response.
                                        .verboseResponses(true)
                                        .build();
            server.start().join();
            return new TestServer(server);
        }

        private final Server server;

        private TestServer(Server server) {
            this.server = server;
        }

        WebClient client() {
            return WebClient.of("http://127.0.0.1:" + server.activeLocalPort());
        }

        @Override
        public void close() {
            server.stop().join();
        }
    }
}
