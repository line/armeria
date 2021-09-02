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
package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

/**
 * A skeletal {@link HttpFile} that simplifies the streaming of potentially large content.
 *
 * @param <T> the type of the stream where the file content is read from, e.g. {@link InputStream}.
 */
public abstract class StreamingHttpFile<T extends Closeable> extends AbstractHttpFile {

    private static final Logger logger = LoggerFactory.getLogger(StreamingHttpFile.class);

    private static final int MAX_CHUNK_SIZE = 8192;

    private static final UnmodifiableFuture<AggregatedHttpFile> NON_EXISTENT_FILE_FUTURE =
            UnmodifiableFuture.completedFuture(NonExistentAggregatedHttpFile.INSTANCE);

    /**
     * Creates a new instance.
     *
     * @param contentType the {@link MediaType} of the file which will be used as the {@code "content-type"}
     *                    header value. {@code null} to disable setting the {@code "content-type"} header.
     * @param clock the {@link Clock} which provides the current date and time
     * @param dateEnabled whether to set the {@code "date"} header automatically
     * @param lastModifiedEnabled whether to add the {@code "last-modified"} header automatically
     * @param entityTagFunction the {@link BiFunction} that generates an entity tag from the file's attributes.
     *                          {@code null} to disable setting the {@code "etag"} header.
     * @param headers the additional headers to set
     */
    protected StreamingHttpFile(@Nullable MediaType contentType,
                                Clock clock,
                                boolean dateEnabled,
                                boolean lastModifiedEnabled,
                                @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                                HttpHeaders headers) {
        super(contentType, clock, dateEnabled, lastModifiedEnabled, entityTagFunction, headers);
    }

    @Override
    protected final HttpResponse doRead(ResponseHeaders headers, long length,
                                        Executor fileReadExecutor, ByteBufAllocator alloc) throws IOException {
        @Nullable
        final T in = newStream();
        if (in == null) {
            return null;
        }

        boolean submitted = false;
        try {
            final HttpResponseWriter res = HttpResponse.streaming();
            res.write(headers);
            fileReadExecutor.execute(() -> doRead(res, in, 0, length, fileReadExecutor, alloc));
            submitted = true;
            return res;
        } finally {
            if (!submitted) {
                close(in);
            }
        }
    }

    private void doRead(HttpResponseWriter res, T in, long offset, long end,
                        Executor fileReadExecutor, ByteBufAllocator alloc) {
        final int chunkSize = (int) Math.min(MAX_CHUNK_SIZE, end - offset);
        final ByteBuf buf = alloc.buffer(chunkSize);
        final int readBytes;
        boolean success = false;
        try {
            readBytes = read(in, buf);
            if (readBytes < 0) {
                // Should not reach here because we only read up to the end of the stream.
                // If reached, it may mean the stream has been truncated.
                throw new EOFException();
            }
            success = true;
        } catch (Exception e) {
            close(res, in, e);
            return;
        } finally {
            if (!success) {
                buf.release();
            }
        }

        final long nextOffset = offset + readBytes;
        final boolean endOfStream = nextOffset == end;
        if (readBytes > 0) {
            if (!res.tryWrite(HttpData.wrap(buf).withEndOfStream(endOfStream))) {
                close(in);
                return;
            }
        } else {
            buf.release();
        }

        if (endOfStream) {
            close(res, in);
            return;
        }

        res.whenConsumed().thenRun(() -> {
            try {
                fileReadExecutor.execute(() -> doRead(res, in, nextOffset, end, fileReadExecutor, alloc));
            } catch (Exception e) {
                close(res, in, e);
            }
        });
    }

    @Override
    public final CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        return doAggregate(fileReadExecutor, null);
    }

    @Override
    public final CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                                  ByteBufAllocator alloc) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");
        requireNonNull(alloc, "alloc");
        return doAggregate(fileReadExecutor, alloc);
    }

    private CompletableFuture<AggregatedHttpFile> doAggregate(Executor fileReadExecutor,
                                                              @Nullable ByteBufAllocator alloc) {
        return readAttributes(fileReadExecutor).thenCompose(attrs -> {
            if (attrs == null) {
                return NON_EXISTENT_FILE_FUTURE;
            }

            if (attrs.length() > Integer.MAX_VALUE) {
                return UnmodifiableFuture.exceptionallyCompletedFuture(
                        new IOException("too large to aggregate: " + attrs.length() + " bytes"));
            }

            @Nullable
            final T in;
            try {
                in = newStream();
            } catch (IOException e) {
                return Exceptions.throwUnsafely(e);
            }

            if (in == null) {
                return NON_EXISTENT_FILE_FUTURE;
            }

            boolean submitted = false;
            try {
                final CompletableFuture<AggregatedHttpFile> future = new EventLoopCheckingFuture<>();
                fileReadExecutor.execute(() -> {
                    final int length = (int) attrs.length();
                    @Nullable
                    final byte[] array;
                    final ByteBuf buf;
                    if (alloc != null) {
                        array = null;
                        buf = alloc.buffer(length);
                    } else {
                        array = new byte[length];
                        buf = Unpooled.wrappedBuffer(array).clear();
                    }

                    boolean success = false;
                    try {
                        for (int offset = 0;;) {
                            final int readBytes = read(in, buf);
                            if (readBytes < 0) {
                                // Should not reach here because we only read up to the end of the stream.
                                // If reached, it may mean the stream has been truncated.
                                throw new EOFException();
                            }

                            offset += readBytes;
                            if (offset == length) {
                                break;
                            }
                        }

                        final HttpData data = (array != null ? HttpData.wrap(array)
                                                             : HttpData.wrap(buf)).withEndOfStream();
                        final AggregatedHttpFileBuilder builder =
                                AggregatedHttpFile.builder(data, attrs.lastModifiedMillis())
                                                  .date(isDateEnabled())
                                                  .lastModified(isLastModifiedEnabled());

                        if (contentType() != null) {
                            builder.contentType(contentType());
                        }

                        @Nullable
                        final String etag = generateEntityTag(attrs);
                        if (etag != null) {
                            builder.entityTag((unused1, unused2) -> etag);
                        } else {
                            builder.entityTag(false);
                        }

                        builder.setHeaders(additionalHeaders());
                        success = future.complete(builder.build());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    } finally {
                        close(in);
                        if (!success) {
                            buf.release();
                        }
                    }
                });

                submitted = true;
                return future;
            } finally {
                if (!submitted) {
                    close(in);
                }
            }
        });
    }

    /**
     * Opens a new stream which reads from the file.
     *
     * @return the new stream, or {@code null} if the file does not exist.
     *
     * @throws IOException if failed to open a new stream
     */
    @Nullable
    protected abstract T newStream() throws IOException;

    /**
     * Reads the content of {@code src} into {@code dst}.
     *
     * @return the number of read bytes, or {@code -1} if reached at the end of the file
     *
     * @throws IOException if failed to read the content
     */
    protected abstract int read(T src, ByteBuf dst) throws IOException;

    private void close(HttpResponseWriter res, Closeable in) {
        close(in);
        res.close();
    }

    private void close(HttpResponseWriter res, Closeable in, Exception cause) {
        close(in);
        res.close(cause);
    }

    private void close(Closeable in) {
        try {
            in.close();
        } catch (Exception e) {
            logger.warn("Failed to close a stream for: {}", this, e);
        }
    }
}
