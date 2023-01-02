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
package com.linecorp.armeria.client.retrofit2;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.concurrent.GuardedBy;

import com.linecorp.armeria.common.annotation.Nullable;

import okio.Buffer;
import okio.Source;
import okio.Timeout;

final class PipeBuffer {
    @GuardedBy("reentrantLock")
    private final Buffer buffer = new Buffer();
    private final PipeSource source = new PipeSource();
    private boolean sinkClosed;
    private boolean sourceClosed;
    private final ReentrantLock reentrantLock = new ReentrantLock();
    @Nullable
    private Throwable sinkClosedException;

    void write(byte[] source, int offset, int byteCount) {
        if (byteCount == 0) {
            return;
        }
        reentrantLock.lock();
        try {
            if (sourceClosed) {
                return;
            }
            if (sinkClosed) {
                throw new IllegalStateException("closed");
            }
            buffer.write(source, offset, byteCount);
            buffer.notifyAll();
        } finally{
            reentrantLock.unlock();
        }
    }

    void close(@Nullable Throwable throwable) {
        reentrantLock.lock();
        try {
            if (sinkClosed) {
                return;
            }
            sinkClosed = true;
            sinkClosedException = throwable;
            buffer.notifyAll();
        } finally{
            reentrantLock.unlock();
        }
    }

    Source source() {
        return source;
    }

    boolean exhausted() {
        reentrantLock.lock();
        try {
            return buffer.exhausted();
        } finally{
            reentrantLock.unlock();
        }
    }

    private final class PipeSource implements Source {
        final Timeout timeout = new Timeout();

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            reentrantLock.lock();
            try {
                if (sourceClosed) {
                    throw new IllegalStateException("closed");
                }

                while (buffer.size() == 0) {
                    if (sinkClosed) {
                        if (sinkClosedException == null) {
                            return -1L;
                        }
                        throw new IOException(sinkClosedException);
                    }
                    timeout.waitUntilNotified(buffer);
                }

                final long result = buffer.read(sink, byteCount);
                buffer.notifyAll();
                return result;
            } finally{
                reentrantLock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            reentrantLock.lock();
            try {
                sourceClosed = true;
                buffer.notifyAll();
            } finally{
                reentrantLock.unlock();
            }
        }

        @Override
        public Timeout timeout() {
            return timeout;
        }
    }
}
