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

package com.linecorp.armeria.server.http;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.EnumSet;
import java.util.Set;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServiceCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Promise;

final class HttpServiceCodec implements ServiceCodec {

    private static final Set<SessionProtocol> ALLOWED_PROTOCOLS = EnumSet.of(
            SessionProtocol.H1, SessionProtocol.H1C,
            SessionProtocol.H2, SessionProtocol.H2C,
            SessionProtocol.HTTP, SessionProtocol.HTTPS);

    private final String loggerName;

    HttpServiceCodec(String loggerName) {
        this.loggerName = loggerName;
    }

    @Override
    public DecodeResult decodeRequest(Channel ch, SessionProtocol sessionProtocol, String hostname,
                                      String path, String mappedPath, ByteBuf in,
                                      Object originalRequest, Promise<Object> promise) throws Exception {

        if (!ALLOWED_PROTOCOLS.contains(sessionProtocol)) {
            throw new IllegalStateException("unsupported session protocol: " + sessionProtocol);
        }

        return new HttpServiceInvocationContext(
                ch, Scheme.of(SerializationFormat.NONE, sessionProtocol),
                hostname, path, mappedPath, loggerName, (FullHttpRequest) originalRequest);
    }

    @Override
    public boolean failureResponseFailsSession(ServiceInvocationContext ctx) {
        return true;
    }

    @Override
    public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
        throw new IllegalStateException("unsupported message type: " + response.getClass().getName());
    }

    @Override
    public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
        final StringWriter sw = new StringWriter(512);
        final PrintWriter pw = new PrintWriter(sw);
        cause.printStackTrace(pw);
        pw.flush();
        return Unpooled.wrappedBuffer(sw.toString().getBytes(CharsetUtil.UTF_8));
    }
}
