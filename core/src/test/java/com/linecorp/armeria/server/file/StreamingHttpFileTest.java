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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Closeable;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

class StreamingHttpFileTest {

    private static final int LENGTH = 3 * 8192;
    private static final Executor executor = MoreExecutors.directExecutor();

    @Test
    void closeStreamWhenAbortedBetweenChunks() {
        final TrackingHttpFile file = new TrackingHttpFile();
        final HttpResponse res = file.read(executor, UnpooledByteBufAllocator.DEFAULT).join();
        assertThat(file.stream.closed).isFalse();

        res.abort();

        assertThat(file.stream.closed).isTrue();
    }

    @Test
    void closeStreamWhenFullyConsumed() {
        final TrackingHttpFile file = new TrackingHttpFile();
        final HttpResponse res = file.read(executor, UnpooledByteBufAllocator.DEFAULT).join();

        assertThat(res.aggregate().join().content().length()).isEqualTo(LENGTH);
        assertThat(file.stream.closed).isTrue();
    }

    private static final class TrackingStream implements Closeable {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingHttpFile extends StreamingHttpFile<TrackingStream> {

        @Nullable
        TrackingStream stream;

        TrackingHttpFile() {
            super(null, Clock.systemUTC(), false, false, null, HttpHeaders.of());
        }

        @Override
        protected String pathOrUri() {
            return "/tracking";
        }

        @Override
        public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
            return UnmodifiableFuture.completedFuture(new HttpFileAttributes(LENGTH, 0));
        }

        @Override
        protected TrackingStream newStream() {
            stream = new TrackingStream();
            return stream;
        }

        @Override
        protected int read(TrackingStream src, ByteBuf dst) {
            final int readBytes = dst.writableBytes();
            dst.writeZero(readBytes);
            return readBytes;
        }
    }
}
