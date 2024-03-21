/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.client.ClosedStreamExceptionUtil.newClosedSessionException;
import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayDeque;
import java.util.Map.Entry;
import java.util.Queue;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public abstract class Http1ObjectEncoder implements HttpObjectEncoder {

    /**
     * The maximum allowed length of an HTTP chunk when TLS is enabled.
     * <ul>
     *   <li>16384 - The maximum length of a cleartext TLS record.</li>
     *   <li>6 - The maximum header length of an HTTP chunk. i.e. "4000\r\n".length()</li>
     *   <li>2 - The trailing "\r\n".</li>
     * </ul>
     *
     * <p>To be precise, we have a chance of wasting 8 bytes because we may not use chunked encoding,
     * but it is not worth adding complexity to be that precise.
     *
     * <p>TODO(trustin): Remove this field as well as {@link #doWriteSplitData(int, HttpData, boolean)}
     *                   once https://github.com/netty/netty/issues/11792 is fixed.
     */
    private static final int MAX_TLS_DATA_LENGTH = 16384 - 8;

    /**
     * A non-last empty {@link HttpContent}.
     */
    private static final HttpContent EMPTY_CONTENT = new DefaultHttpContent(Unpooled.EMPTY_BUFFER);

    private final Channel ch;
    private final SessionProtocol protocol;

    private volatile boolean closed;

    /**
     * The ID of the request which is at its turn to send a response.
     */
    private int currentId = 1;

    /**
     * The minimum ID of the request whose stream has been closed/reset.
     */
    private int minClosedId = Integer.MAX_VALUE;

    /**
     * The maximum known ID with pending writes.
     */
    private int maxIdWithPendingWrites = Integer.MIN_VALUE;

    /**
     * The map which maps a request ID to its related pending response.
     */
    private final IntObjectMap<PendingWrites> pendingWritesMap = new IntObjectHashMap<>();

    protected Http1ObjectEncoder(Channel ch, SessionProtocol protocol) {
        this.ch = requireNonNull(ch, "ch");
        this.protocol = requireNonNull(protocol, "protocol");
    }

    @Override
    public final Channel channel() {
        return ch;
    }

    protected final ChannelFuture writeNonInformationalHeaders(
            int id, HttpObject converted, boolean endStream, ChannelPromise promise) {
        ChannelFuture f;
        if (converted instanceof LastHttpContent) {
            assert endStream;
            f = write(id, converted, true, promise);
        } else {
            f = write(id, converted, false, promise);
            if (endStream) {
                final ChannelFuture lastFuture = write(id, LastHttpContent.EMPTY_LAST_CONTENT, true);
                if (Flags.verboseExceptionSampler().isSampled(Http1VerboseWriteException.class)) {
                    f = combine(f, lastFuture);
                } else {
                    f = lastFuture;
                }
            }
        }
        ch.flush();
        return f;
    }

    private ChannelPromise combine(ChannelFuture first, ChannelFuture second) {
        final ChannelPromise promise = channel().newPromise();
        final FutureListener<Void> listener = new FutureListener<Void>() {
            private int remaining = 2;

            @Override
            public void operationComplete(Future<Void> ignore) throws Exception {
                remaining--;
                if (remaining == 0) {
                    final Throwable firstCause = first.cause();
                    final Throwable secondCause = second.cause();

                    final Throwable combinedCause;
                    if (firstCause == null) {
                        combinedCause = secondCause;
                    } else {
                        if (secondCause != null && secondCause != firstCause) {
                            firstCause.addSuppressed(secondCause);
                        }
                        combinedCause = firstCause;
                    }
                    if (combinedCause != null) {
                        promise.setFailure(combinedCause);
                    } else {
                        promise.setSuccess();
                    }
                }
            }
        };
        first.addListener(listener);
        second.addListener(listener);
        return promise;
    }

    @Override
    public final ChannelFuture doWriteData(int id, int streamId, HttpData data, boolean endStream) {
        if (!isWritable(id)) {
            data.close();
            return newClosedSessionFuture();
        }

        final int length = data.length();
        if (length == 0) {
            data.close();
            final HttpContent content = endStream ? LastHttpContent.EMPTY_LAST_CONTENT : EMPTY_CONTENT;
            final ChannelFuture future = write(id, content, endStream);
            ch.flush();
            return future;
        }

        try {
            if (!protocol.isTls() || length <= MAX_TLS_DATA_LENGTH) {
                // Cleartext connection or data.length() <= MAX_TLS_DATA_LENGTH
                return doWriteUnsplitData(id, data, endStream);
            } else {
                // TLS or data.length() > MAX_TLS_DATA_LENGTH
                return doWriteSplitData(id, data, endStream);
            }
        } catch (Throwable t) {
            return newFailedFuture(t);
        }
    }

    private ChannelFuture doWriteUnsplitData(int id, HttpData data, boolean endStream) {
        final ByteBuf buf = toByteBuf(data);
        boolean handled = false;
        try {
            final HttpContent content;
            if (endStream) {
                content = new DefaultLastHttpContent(buf);
            } else {
                content = new DefaultHttpContent(buf);
            }

            final ChannelFuture future = write(id, content, endStream);
            handled = true;
            ch.flush();
            return future;
        } finally {
            if (!handled) {
                buf.release();
            }
        }
    }

    private ChannelFuture doWriteSplitData(int id, HttpData data, boolean endStream) {
        try {
            int offset = 0;
            int remaining = data.length();
            ChannelFuture lastFuture;
            for (;;) {
                // Ensure an HttpContent does not exceed the maximum length of a cleartext TLS record.
                final int chunkSize = Math.min(MAX_TLS_DATA_LENGTH, remaining);
                lastFuture = write(id, new DefaultHttpContent(toByteBuf(data, offset, chunkSize)), false);
                remaining -= chunkSize;
                if (remaining == 0) {
                    break;
                }
                offset += chunkSize;
            }

            if (endStream) {
                lastFuture = write(id, LastHttpContent.EMPTY_LAST_CONTENT, true);
            }

            ch.flush();
            return lastFuture;
        } finally {
            data.close();
        }
    }

    protected final ChannelFuture write(int id, HttpObject obj, boolean endStream) {
        return write(id, obj, endStream, ch.newPromise());
    }

    final ChannelFuture write(int id, HttpObject obj, boolean endStream, ChannelPromise promise) {
        if (id < currentId) {
            // Attempted to write something on a finished request/response; discard.
            // e.g. the request already timed out.
            ReferenceCountUtil.release(obj);
            promise.setFailure(ClosedSessionException.get());
            return promise;
        }

        final PendingWrites currentPendingWrites = pendingWritesMap.get(id);
        if (id == currentId) {
            if (currentPendingWrites != null) {
                pendingWritesMap.remove(id);
                flushPendingWrites(currentPendingWrites);
            }

            final ChannelFuture future = write(obj, promise);
            if (!isPing(id)) {
                keepAliveHandler().onReadOrWrite();
            }
            if (endStream) {
                currentId++;

                // The next PendingWrites might be complete already.
                for (;;) {
                    final PendingWrites nextPendingWrites = pendingWritesMap.get(currentId);
                    if (nextPendingWrites == null) {
                        break;
                    }

                    flushPendingWrites(nextPendingWrites);
                    if (!nextPendingWrites.isEndOfStream()) {
                        break;
                    }

                    pendingWritesMap.remove(currentId);
                    currentId++;
                }
            }

            return future;
        } else {
            final Entry<HttpObject, ChannelPromise> entry = new SimpleImmutableEntry<>(obj, promise);
            final PendingWrites pendingWrites;
            if (currentPendingWrites == null) {
                pendingWrites = new PendingWrites();
                maxIdWithPendingWrites = Math.max(maxIdWithPendingWrites, id);
                pendingWritesMap.put(id, pendingWrites);
            } else {
                pendingWrites = currentPendingWrites;
            }

            pendingWrites.add(entry);

            if (endStream) {
                pendingWrites.setEndOfStream();
            }

            return promise;
        }
    }

    protected abstract ChannelFuture write(HttpObject obj, ChannelPromise promise);

    protected int currentId() {
        return currentId;
    }

    private void flushPendingWrites(PendingWrites pendingWrites) {
        for (;;) {
            final Entry<HttpObject, ChannelPromise> e = pendingWrites.poll();
            if (e == null) {
                break;
            }

            write(e.getKey(), e.getValue());
        }
    }

    @Override
    public final ChannelFuture doWriteTrailers(int id, int streamId, HttpHeaders headers) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        return write(id, convertTrailers(headers), true);
    }

    private LastHttpContent convertTrailers(HttpHeaders inputHeaders) {
        if (inputHeaders.isEmpty()) {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }

        final LastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, false);
        final io.netty.handler.codec.http.HttpHeaders outputHeaders = lastContent.trailingHeaders();
        convertTrailers(inputHeaders, outputHeaders);
        return lastContent;
    }

    protected abstract void convertTrailers(HttpHeaders inputHeaders,
                                            io.netty.handler.codec.http.HttpHeaders outputHeaders);

    @Override
    public final ChannelFuture doWriteReset(int id, int streamId, Http2Error error, boolean unused) {
        // NB: this.minClosedId can be overwritten more than once when 3+ pipelined requests are received
        //     and they are handled by different threads simultaneously.
        //     e.g. when the 3rd request triggers a reset and then the 2nd one triggers another.
        updateClosedId(id);

        if (minClosedId <= maxIdWithPendingWrites) {
            final ClosedSessionException cause =
                    new ClosedSessionException("An HTTP/1 connection has been reset: " + error);
            for (int i = minClosedId; i <= maxIdWithPendingWrites; i++) {
                final PendingWrites pendingWrites = pendingWritesMap.remove(i);
                for (;;) {
                    final Entry<HttpObject, ChannelPromise> e = pendingWrites.poll();
                    if (e == null) {
                        break;
                    }

                    e.getValue().tryFailure(cause);
                }
            }
        }

        final ChannelFuture f = ch.write(Unpooled.EMPTY_BUFFER);
        if (!isWritable(currentId)) {
            f.addListener(ChannelFutureListener.CLOSE);
        }

        return f;
    }

    @Override
    public final boolean isWritable(int id, int streamId) {
        return isWritable(id);
    }

    protected final boolean isWritable(int id) {
        return id < minClosedId && !isClosed();
    }

    protected final void updateClosedId(int id) {
        minClosedId = Math.min(minClosedId, id);
    }

    protected abstract boolean isPing(int id);

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;

        keepAliveHandler().destroy();
        if (pendingWritesMap.isEmpty()) {
            return;
        }

        final ClosedSessionException cause = newClosedSessionException(ch);
        for (Queue<Entry<HttpObject, ChannelPromise>> queue : pendingWritesMap.values()) {
            for (;;) {
                final Entry<HttpObject, ChannelPromise> e = queue.poll();
                if (e == null) {
                    break;
                }

                e.getValue().tryFailure(cause);
            }
        }

        pendingWritesMap.clear();
    }

    @Override
    public final boolean isClosed() {
        return closed || !channel().isActive();
    }

    private static final class PendingWrites extends ArrayDeque<Entry<HttpObject, ChannelPromise>> {

        private static final long serialVersionUID = 4241891747461017445L;

        private boolean endOfStream;

        PendingWrites() {
            super(4);
        }

        @Override
        public boolean add(Entry<HttpObject, ChannelPromise> httpObjectChannelPromiseEntry) {
            return isEndOfStream() ? false : super.add(httpObjectChannelPromiseEntry);
        }

        boolean isEndOfStream() {
            return endOfStream;
        }

        void setEndOfStream() {
            endOfStream = true;
        }
    }

    protected final SessionProtocol protocol() {
        return protocol;
    }

    /**
     * A dummy {@link Exception} to sample write exceptions.
     */
    @SuppressWarnings("serial")
    private static final class Http1VerboseWriteException extends Exception {
       private Http1VerboseWriteException() {
           // Only the class type is used for sampling.
           throw new UnsupportedOperationException();
       }
    }
}
