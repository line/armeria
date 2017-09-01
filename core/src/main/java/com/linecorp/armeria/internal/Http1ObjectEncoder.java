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

package com.linecorp.armeria.internal;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayDeque;
import java.util.Map.Entry;
import java.util.Queue;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.stream.ClosedPublisherException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

public final class Http1ObjectEncoder extends HttpObjectEncoder {

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

    private final boolean server;
    private final boolean isTls;

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

    public Http1ObjectEncoder(boolean server, boolean isTls) {
        this.server = server;
        this.isTls = isTls;
    }

    @Override
    protected ChannelFuture doWriteHeaders(ChannelHandlerContext ctx, int id, int streamId,
                                           HttpHeaders headers, boolean endStream) {
        if (id >= minClosedId) {
            return ctx.newFailedFuture(ClosedSessionException.get());
        }

        try {
            return server ? writeServerHeaders(ctx, id, streamId, headers, endStream)
                          : writeClientHeaders(ctx, id, streamId, headers, endStream);
        } catch (Throwable t) {
            return ctx.newFailedFuture(t);
        }
    }

    private ChannelFuture writeServerHeaders(
            ChannelHandlerContext ctx, int id, int streamId,
            HttpHeaders headers, boolean endStream) throws Http2Exception {

        final HttpObject converted = convertServerHeaders(streamId, headers, endStream);
        final HttpStatus status = headers.status();
        if (status == null) {
            // Trailing headers
            final ChannelFuture f = write(ctx, id, converted, endStream);
            ctx.flush();
            return f;
        }

        if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
            // Informational status headers.
            final ChannelFuture f = write(ctx, id, converted, false);
            if (endStream) {
                // Can't end a stream with informational status in HTTP/1.
                f.addListener(ChannelFutureListener.CLOSE);
            }
            ctx.flush();
            return f;
        }

        // Non-informational status headers.
        return writeNonInformationalHeaders(ctx, id, converted, endStream);
    }

    private ChannelFuture writeClientHeaders(
            ChannelHandlerContext ctx, int id, int streamId,
            HttpHeaders headers, boolean endStream) throws Http2Exception {

        return writeNonInformationalHeaders(
                ctx, id, convertClientHeaders(streamId, headers, endStream), endStream);
    }

    private ChannelFuture writeNonInformationalHeaders(
            ChannelHandlerContext ctx, int id, HttpObject converted, boolean endStream) {

        ChannelFuture f;
        if (converted instanceof LastHttpContent) {
            assert endStream;
            f = write(ctx, id, converted, true);
        } else {
            f = write(ctx, id, converted, false);
            if (endStream) {
                f = write(ctx, id, LastHttpContent.EMPTY_LAST_CONTENT, true);
            }
        }

        ctx.flush();
        return f;
    }

    private HttpObject convertServerHeaders(
            int streamId, HttpHeaders headers, boolean endStream) throws Http2Exception {

        // Leading headers will always have :status, trailers will never have it.
        final HttpStatus status = headers.status();
        if (status == null) {
            return convertTrailingHeaders(streamId, headers);
        }

        // Convert leading headers.
        final HttpResponse res;
        final boolean informational = status.codeClass() == HttpStatusClass.INFORMATIONAL;
        final HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(status.code());

        if (endStream || informational) {

            res = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, nettyStatus,
                    Unpooled.EMPTY_BUFFER, false);

            final io.netty.handler.codec.http.HttpHeaders outHeaders = res.headers();
            convert(streamId, headers, outHeaders, false);

            if (informational) {
                // 1xx responses does not have the 'content-length' header.
                outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
            } else if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                // NB: Set the 'content-length' only when not set rather than always setting to 0.
                //     It's because a response to a HEAD request can have empty content while having
                //     non-zero 'content-length' header.
                //     However, this also opens the possibility of sending a non-zero 'content-length'
                //     header even when it really has to be zero. e.g. a response to a non-HEAD request
                outHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
        } else {
            res = new DefaultHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, false);
            // Perform conversion.
            convert(streamId, headers, res.headers(), false);
            setTransferEncoding(res);
        }

        return res;
    }

    private HttpObject convertClientHeaders(int streamId, HttpHeaders headers, boolean endStream)
            throws Http2Exception {

        // Leading headers will always have :method, trailers will never have it.
        final HttpMethod method = headers.method();
        if (method == null) {
            return convertTrailingHeaders(streamId, headers);
        }

        // Convert leading headers.
        final HttpRequest req = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.valueOf(method.name()),
                headers.path(), false);

        convert(streamId, headers, req.headers(), false);

        if (endStream) {
            req.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            req.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        } else if (HttpUtil.getContentLength(req, -1L) >= 0) {
            // Avoid the case where both 'content-length' and 'transfer-encoding' are set.
            req.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        } else {
            req.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }

        return req;
    }

    private void convert(
            int streamId, HttpHeaders inHeaders,
            io.netty.handler.codec.http.HttpHeaders outHeaders, boolean trailer) throws Http2Exception {

        ArmeriaHttpUtil.toNettyHttp1(
                streamId, inHeaders, outHeaders, HttpVersion.HTTP_1_1, trailer, false);

        outHeaders.remove(ExtensionHeaderNames.STREAM_ID.text());
        if (server) {
            outHeaders.remove(ExtensionHeaderNames.SCHEME.text());
        } else {
            outHeaders.remove(ExtensionHeaderNames.PATH.text());
        }
    }

    private LastHttpContent convertTrailingHeaders(int streamId, HttpHeaders headers) throws Http2Exception {
        final LastHttpContent lastContent;
        if (headers.isEmpty()) {
            lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
        } else {
            lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, false);
            convert(streamId, headers, lastContent.trailingHeaders(), true);
        }
        return lastContent;
    }

    private static void setTransferEncoding(HttpMessage out) {
        final io.netty.handler.codec.http.HttpHeaders outHeaders = out.headers();
        final long contentLength = HttpUtil.getContentLength(out, -1L);
        if (contentLength < 0) {
            // Use chunked encoding.
            outHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    @Override
    protected ChannelFuture doWriteData(
            ChannelHandlerContext ctx, int id, int streamId, HttpData data, boolean endStream) {

        if (id >= minClosedId) {
            ReferenceCountUtil.safeRelease(data);
            return ctx.newFailedFuture(ClosedSessionException.get());
        }

        final int length = data.length();
        if (length == 0) {
            ReferenceCountUtil.safeRelease(data);
            final HttpContent content = endStream ? LastHttpContent.EMPTY_LAST_CONTENT : EMPTY_CONTENT;
            final ChannelFuture future = write(ctx, id, content, endStream);
            ctx.flush();
            return future;
        }

        try {
            if (!isTls || length <= MAX_TLS_DATA_LENGTH) {
                // Cleartext connection or data.length() <= MAX_TLS_DATA_LENGTH
                return doWriteUnsplitData(ctx, id, data, endStream);
            } else {
                // TLS and data.length() > MAX_TLS_DATA_LENGTH
                return doWriteSplitData(ctx, id, data, endStream);
            }
        } catch (Throwable t) {
            return ctx.newFailedFuture(t);
        }
    }

    private ChannelFuture doWriteUnsplitData(ChannelHandlerContext ctx, int id, HttpData data,
                                             boolean endStream) {
        final ByteBuf buf = toByteBuf(ctx, data);
        boolean handled = false;
        try {
            final HttpContent content;
            if (endStream) {
                content = new DefaultLastHttpContent(buf);
            } else {
                content = new DefaultHttpContent(buf);
            }

            final ChannelFuture future = write(ctx, id, content, endStream);
            handled = true;
            ctx.flush();
            return future;
        } finally {
            if (!handled) {
                ReferenceCountUtil.safeRelease(buf);
            }
        }
    }

    private ChannelFuture doWriteSplitData(
            ChannelHandlerContext ctx, int id, HttpData data, boolean endStream) {

        try {
            int offset = data.offset();
            int remaining = data.length();
            ChannelFuture lastFuture;
            for (;;) {
                // Ensure an HttpContent does not exceed the maximum length of a cleartext TLS record.
                final int chunkSize = Math.min(MAX_TLS_DATA_LENGTH, remaining);
                lastFuture = write(ctx, id, new DefaultHttpContent(dataChunk(data, offset, chunkSize)), false);
                remaining -= chunkSize;
                if (remaining == 0) {
                    break;
                }
                offset += chunkSize;
            }

            if (endStream) {
                lastFuture = write(ctx, id, LastHttpContent.EMPTY_LAST_CONTENT, true);
            }

            ctx.flush();
            return lastFuture;
        } finally {
            ReferenceCountUtil.safeRelease(data);
        }
    }

    private static ByteBuf dataChunk(HttpData data, int offset, int chunkSize) {
        if (data instanceof ByteBufHolder) {
            ByteBuf buf = ((ByteBufHolder) data).content();
            return buf.retainedSlice(offset, chunkSize);
        } else {
            return Unpooled.wrappedBuffer(data.array(), offset, chunkSize);
        }
    }

    private ChannelFuture write(ChannelHandlerContext ctx, int id, HttpObject obj, boolean endStream) {
        if (id < currentId) {
            // Attempted to write something on a finished request/response; discard.
            // e.g. the request already timed out.
            ReferenceCountUtil.safeRelease(obj);
            return ctx.newFailedFuture(ClosedPublisherException.get());
        }

        final PendingWrites currentPendingWrites = pendingWritesMap.get(id);
        if (id == currentId) {
            if (currentPendingWrites != null) {
                pendingWritesMap.remove(id);
                flushPendingWrites(ctx, currentPendingWrites);
            }

            final ChannelFuture future = ctx.write(obj);
            if (endStream) {
                currentId++;

                // The next PendingWrites might be complete already.
                for (;;) {
                    final PendingWrites nextPendingWrites = pendingWritesMap.get(currentId);
                    if (nextPendingWrites == null) {
                        break;
                    }

                    flushPendingWrites(ctx, nextPendingWrites);
                    if (!nextPendingWrites.isEndOfStream()) {
                        break;
                    }

                    pendingWritesMap.remove(currentId);
                    currentId++;
                }
            }

            return future;
        } else {
            final ChannelPromise promise = ctx.newPromise();
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

    private static void flushPendingWrites(ChannelHandlerContext ctx, PendingWrites pendingWrites) {
        for (;;) {
            final Entry<HttpObject, ChannelPromise> e = pendingWrites.poll();
            if (e == null) {
                break;
            }

            ctx.write(e.getKey(), e.getValue());
        }
    }

    @Override
    protected ChannelFuture doWriteReset(ChannelHandlerContext ctx, int id, int streamId, Http2Error error) {
        // NB: this.minClosedId can be overwritten more than once when 3+ pipelined requests are received
        //     and they are handled by different threads simultaneously.
        //     e.g. when the 3rd request triggers a reset and then the 2nd one triggers another.
        minClosedId = Math.min(minClosedId, id);
        for (int i = minClosedId; i <= maxIdWithPendingWrites; i++) {
            final PendingWrites pendingWrites = pendingWritesMap.remove(i);
            for (;;) {
                final Entry<HttpObject, ChannelPromise> e = pendingWrites.poll();
                if (e == null) {
                    break;
                }
                e.getValue().tryFailure(ClosedSessionException.get());
            }
        }

        final ChannelFuture f = ctx.write(Unpooled.EMPTY_BUFFER);
        if (currentId >= minClosedId) {
            f.addListener(ChannelFutureListener.CLOSE);
        }

        return f;
    }

    @Override
    protected void doClose() {
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
}
