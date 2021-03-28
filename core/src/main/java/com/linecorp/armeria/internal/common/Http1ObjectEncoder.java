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

import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayDeque;
import java.util.Map.Entry;
import java.util.Queue;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;

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

public abstract class Http1ObjectEncoder implements HttpObjectEncoder {

    /**
     * The maximum allowed length of an HTTP chunk when TLS is enabled.
     * <ul>
     *   <li>16384 - The maximum length of a cleartext TLS record.</li>
     *   <li>6 - The maximum header length of an HTTP chunk. i.e. "4000\r\n".length()</li>
     * </ul>
     *
     * <p>To be precise, we have a chance of wasting 6 bytes because we may not use chunked encoding,
     * but it is not worth adding complexity to be that precise.
     */
    private static final int MAX_TLS_DATA_LENGTH = 16384 - 6;

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
    private boolean closeWhenCurrentStreamEnds;

    protected Http1ObjectEncoder(Channel ch, SessionProtocol protocol) {
        this.ch = requireNonNull(ch, "ch");
        this.protocol = requireNonNull(protocol, "protocol");
    }

    public void closeWhenCurrentStreamEnds() {
        closeWhenCurrentStreamEnds = true;
    }

    @Override
    public final Channel channel() {
        return ch;
    }

    protected final ChannelFuture writeNonInformationalHeaders(int id, HttpObject converted,
                                                               boolean endStream) {
        ChannelFuture f;
        if (converted instanceof LastHttpContent) {
            assert endStream;
            f = write(id, converted, true);
        } else {
            f = write(id, converted, false);
            if (endStream) {
                f = write(id, LastHttpContent.EMPTY_LAST_CONTENT, true);
            }
        }
        ch.flush();
        return f;
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
        if (id < currentId) {
            // Attempted to write something on a finished request/response; discard.
            // e.g. the request already timed out.
            ReferenceCountUtil.release(obj);
            return newFailedFuture(ClosedStreamException.get());
        }

        final PendingWrites currentPendingWrites = pendingWritesMap.get(id);
        if (id == currentId) {
            if (currentPendingWrites != null) {
                pendingWritesMap.remove(id);
                flushPendingWrites(currentPendingWrites);
            }

            final ChannelFuture future = ch.write(obj);
            if (!isPing(id)) {
                keepAliveHandler().onReadOrWrite();
            }
            if (endStream) {
                if (closeWhenCurrentStreamEnds) {
                    future.addListener(ChannelFutureListener.CLOSE);
                } else {
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
            }

            return future;
        } else {
            final ChannelPromise promise = ch.newPromise();
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

    private void flushPendingWrites(PendingWrites pendingWrites) {
        for (;;) {
            final Entry<HttpObject, ChannelPromise> e = pendingWrites.poll();
            if (e == null) {
                break;
            }

            ch.write(e.getKey(), e.getValue());
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
    public final ChannelFuture doWriteReset(int id, int streamId, Http2Error error) {
        // NB: this.minClosedId can be overwritten more than once when 3+ pipelined requests are received
        //     and they are handled by different threads simultaneously.
        //     e.g. when the 3rd request triggers a reset and then the 2nd one triggers another.
        minClosedId = Math.min(minClosedId, id);

        if (minClosedId <= maxIdWithPendingWrites) {
            final ClosedSessionException cause =
                    new ClosedSessionException("An HTTP/1 stream has been reset: " + error);
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
        return id < minClosedId;
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

        final ClosedSessionException cause = ClosedSessionException.get();
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
        return closed;
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
}
