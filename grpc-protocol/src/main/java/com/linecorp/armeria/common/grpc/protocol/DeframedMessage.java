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

package com.linecorp.armeria.common.grpc.protocol;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.buffer.ByteBuf;

/**
 * A deframed message. For uncompressed messages, we have the entire buffer available and return it
 * as is in {@code buf} to optimize parsing. For compressed messages, we will parse incrementally
 * and thus return an {@link InputStream} in {@code stream}.
 */
@UnstableApi
public final class DeframedMessage implements SafeCloseable {
    private final int type;

    @Nullable
    private final ByteBuf buf;
    @Nullable
    private final InputStream stream;
    private boolean closed;

    /**
     * Creates a new instance with the specified {@link ByteBuf} and {@code type}.
     */
    @VisibleForTesting
    public DeframedMessage(ByteBuf buf, int type) {
        this(requireNonNull(buf, "buf"), null, type);
    }

    /**
     * Creates a new instance with the specified {@link InputStream} and {@code type}.
     */
    @VisibleForTesting
    public DeframedMessage(InputStream stream, int type) {
        this(null, requireNonNull(stream, "stream"), type);
    }

    private DeframedMessage(@Nullable ByteBuf buf, @Nullable InputStream stream, int type) {
        this.buf = buf;
        this.stream = stream;
        this.type = type;
    }

    /**
     * Returns the {@link ByteBuf}.
     *
     * @return the {@link ByteBuf}, or {@code null} if not created with
     *         {@link #DeframedMessage(ByteBuf, int)}.
     */
    @Nullable
    public ByteBuf buf() {
        return buf;
    }

    /**
     * Returns the {@link InputStream}.
     *
     * @return the {@link InputStream}, or {@code null} if not created with
     *         {@link #DeframedMessage(InputStream, int)}.
     */
    @Nullable
    public InputStream stream() {
        return stream;
    }

    /**
     * Returns {@code true} if this message is trailer.
     */
    public boolean isTrailer() {
        return type >> 7 == 1;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeframedMessage)) {
            return false;
        }

        final DeframedMessage that = (DeframedMessage) o;

        return type == that.type && Objects.equals(buf, that.buf) && Objects.equals(stream, that.stream);
    }

    @Override
    public int hashCode() {
        return Objects.hash(buf, stream);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (buf != null) {
            buf.release();
        } else {
            try {
                stream.close();
            } catch (IOException e) {
                // Ignore silently
            }
        }
    }
}
