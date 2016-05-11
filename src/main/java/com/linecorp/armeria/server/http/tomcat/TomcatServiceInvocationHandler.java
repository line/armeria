/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.tomcat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.Executor;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.Adapter;
import org.apache.coyote.InputBuffer;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Request;
import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.MimeHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.ServiceUnavailableException;
import com.linecorp.armeria.server.ServiceInvocationHandler;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Promise;

class TomcatServiceInvocationHandler implements ServiceInvocationHandler {

    private final String hostname;

    private final Connector connector;

    TomcatServiceInvocationHandler(String hostname, Connector connector) {
        this.hostname = hostname;
        this.connector = connector;
    }

    Connector connector() {
        return connector;
    }

    @Override
    public void invoke(ServiceInvocationContext ctx,
                       Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {
        final Adapter coyoteAdapter = connector.getProtocolHandler().getAdapter();
        if (coyoteAdapter == null) {
            // Tomcat is not configured / stopped.
            promise.tryFailure(new ServiceUnavailableException());
            return;
        }

        final Request coyoteReq = convertRequest(ctx);
        final Response coyoteRes = new Response();
        coyoteReq.setResponse(coyoteRes);
        coyoteRes.setRequest(coyoteReq);

        final ByteBuf resContent = ctx.alloc().ioBuffer();
        coyoteRes.setOutputBuffer(new OutputBuffer() {
            private long bytesWritten;

            @Override
            public int doWrite(ByteChunk chunk, Response response) {
                final int length = chunk.getLength();
                resContent.writeBytes(chunk.getBuffer(), chunk.getStart(), length);
                bytesWritten += length;
                return length;
            }

            @Override
            public long getBytesWritten() {
                return bytesWritten;
            }
        });

        blockingTaskExecutor.execute(() -> {
            if (promise.isDone()) {
                return;
            }

            ServiceInvocationContext.setCurrent(ctx);
            try {
                coyoteAdapter.service(coyoteReq, coyoteRes);
                ctx.resolvePromise(promise, convertResponse(coyoteRes, resContent));
            } catch (Throwable t) {
                ctx.rejectPromise(promise, t);
            } finally {
                ServiceInvocationContext.removeCurrent();
            }
        });
    }

    private Request convertRequest(ServiceInvocationContext ctx) {
        final FullHttpRequest req = ctx.originalRequest();
        final String mappedPath = ctx.mappedPath();

        final Request coyoteReq = new Request();

        // Set the remote host/address.
        final InetSocketAddress remoteAddr = (InetSocketAddress) ctx.remoteAddress();
        coyoteReq.remoteAddr().setString(remoteAddr.getAddress().getHostAddress());
        coyoteReq.remoteHost().setString(remoteAddr.getHostString());
        coyoteReq.setRemotePort(remoteAddr.getPort());

        // Set the local host/address.
        final InetSocketAddress localAddr = (InetSocketAddress) ctx.localAddress();
        coyoteReq.localAddr().setString(localAddr.getAddress().getHostAddress());
        coyoteReq.localName().setString(hostname);
        coyoteReq.setLocalPort(localAddr.getPort());

        // Set the method.
        final HttpMethod method = req.method();
        coyoteReq.method().setString(method.name());

        // Set the request URI.
        final byte[] uriBytes = mappedPath.getBytes(StandardCharsets.US_ASCII);
        coyoteReq.requestURI().setBytes(uriBytes, 0, uriBytes.length);

        // Set the query string if any.
        final int queryIndex = req.uri().indexOf('?');
        if (queryIndex >= 0) {
            coyoteReq.queryString().setString(req.uri().substring(queryIndex + 1));
        }

        // Set the headers.
        final MimeHeaders cHeaders = coyoteReq.getMimeHeaders();
        convertHeaders(req.headers(), cHeaders);
        convertHeaders(req.trailingHeaders(), cHeaders);

        // Set the content.
        final ByteBuf content = req.content();
        if (content.isReadable()) {
            coyoteReq.setInputBuffer(new InputBuffer() {
                private boolean read;

                @Override
                public int doRead(ByteChunk chunk, Request request) {
                    if (read) {
                        // Read only once.
                        return -1;
                    }

                    read = true;

                    final int readableBytes = content.readableBytes();
                    if (content.hasArray()) {
                        // Note that we do not increase the reference count of the request (and thus its
                        // content as well) in spite that setBytes() does not perform a deep copy,
                        // because it will not be released until the invocation is handled completely.
                        // See HttpServerHandler.handleInvocationResult() for more information.
                        chunk.setBytes(content.array(),
                                       content.arrayOffset() + content.readerIndex(),
                                       readableBytes);
                    } else {
                        final byte[] buf = new byte[readableBytes];
                        content.getBytes(content.readerIndex(), buf);
                        chunk.setBytes(buf, 0, buf.length);
                    }

                    return readableBytes;
                }
            });
        }

        return coyoteReq;
    }

    private static void convertHeaders(HttpHeaders headers, MimeHeaders cHeaders) {
        if (headers.isEmpty()) {
            return;
        }

        for (Iterator<Entry<CharSequence, CharSequence>> i = headers.iteratorCharSequence(); i.hasNext();) {
            final Entry<CharSequence, CharSequence> e = i.next();
            final CharSequence k = e.getKey();
            final CharSequence v = e.getValue();

            final MessageBytes cValue;
            if (k instanceof AsciiString) {
                final AsciiString ak = (AsciiString) k;
                cValue = cHeaders.addValue(ak.array(), ak.arrayOffset(), ak.length());
            } else {
                cValue = cHeaders.addValue(k.toString());
            }

            if (v instanceof AsciiString) {
                final AsciiString av = (AsciiString) v;
                cValue.setBytes(av.array(), av.arrayOffset(), av.length());
            } else {
                final byte[] valueBytes = v.toString().getBytes(StandardCharsets.US_ASCII);
                cValue.setBytes(valueBytes, 0, valueBytes.length);
            }
        }
    }

    private static FullHttpResponse convertResponse(Response coyoteRes, ByteBuf content) {
        final FullHttpResponse res = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(coyoteRes.getStatus()), content);

        final HttpHeaders headers = res.headers();

        final String contentType = coyoteRes.getContentType();
        if (contentType != null && !contentType.isEmpty()) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        final MimeHeaders cHeaders = coyoteRes.getMimeHeaders();
        final int numHeaders = cHeaders.size();
        for (int i = 0; i < numHeaders; i++) {
            headers.add(convertMessageBytes(cHeaders.getName(i)),
                        convertMessageBytes(cHeaders.getValue(i)));
        }

        return res;
    }

    private static CharSequence convertMessageBytes(MessageBytes value) {
        if (value.getType() != MessageBytes.T_BYTES) {
            return value.toString();
        }

        final ByteChunk chunk = value.getByteChunk();
        return new AsciiString(chunk.getBuffer(), chunk.getOffset(), chunk.getLength(), false);
    }
}
