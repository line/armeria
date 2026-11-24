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
package com.linecorp.armeria.internal.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongFunction;

/**
 * An {@link InputStream} that fails when more than the specified number of bytes are read.
 */
public final class SizeEnforcingInputStream extends FilterInputStream {

    private final int maxLength;
    private final LongFunction<? extends RuntimeException> exceptionFactory;
    private long count;
    private long mark = -1;

    /**
     * Creates a new instance with the specified limit and exception factory.
     */
    public SizeEnforcingInputStream(InputStream in, int maxLength,
                                    LongFunction<? extends RuntimeException> exceptionFactory) {
        super(requireNonNull(in, "in"));
        checkArgument(maxLength >= 0, "maxLength: %s (expected: >= 0)", maxLength);
        this.maxLength = maxLength;
        this.exceptionFactory = requireNonNull(exceptionFactory, "exceptionFactory");
    }

    @Override
    public int read() throws IOException {
        final int result = in.read();
        if (result != -1) {
            count++;
        }
        verifySize();
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        final int result = in.read(b, off, len);
        if (result != -1) {
            count += result;
        }
        verifySize();
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        final long result = in.skip(n);
        count += result;
        verifySize();
        return result;
    }

    @Override
    public synchronized void mark(int readlimit) {
        in.mark(readlimit);
        mark = count;
    }

    @Override
    public synchronized void reset() throws IOException {
        if (!in.markSupported()) {
            throw new IOException("Mark not supported");
        }
        if (mark == -1) {
            throw new IOException("Mark not set");
        }

        in.reset();
        count = mark;
    }

    private void verifySize() {
        if (count > maxLength) {
            throw exceptionFactory.apply(count);
        }
    }
}
