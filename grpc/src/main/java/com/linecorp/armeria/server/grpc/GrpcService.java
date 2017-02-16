/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.TransportFrameUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.AsciiString;

/**
 * A {@link AbstractHttpService} that implements the GRPC wire protocol.
 */
public final class GrpcService extends AbstractHttpService {

    private static final Metadata EMPTY_METADATA = new Metadata();

    private final InternalHandlerRegistry registry;
    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;

    private long maxMessageSize = -1;

    GrpcService(InternalHandlerRegistry registry,
                DecompressorRegistry decompressorRegistry,
                CompressorRegistry compressorRegistry) {
        this.registry = requireNonNull(registry, "registry");
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) throws Exception {
        if (!verifyContentType(req.headers())) {
            res.respond(HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Missing or invalid Content-Type header.");
            return;
        }
        String methodName = determineMethod(ctx);
        if (methodName == null) {
            res.respond(HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Invalid path.");
            return;
        }

        ArmeriaGrpcServerStream stream = new ArmeriaGrpcServerStream(res, maxMessageSize);

        ServerMethodDefinition<?, ?> method = registry.lookupMethod(methodName);
        if (method == null) {
            stream.close(Status.UNIMPLEMENTED.withDescription("Method not found: " + methodName),
                         EMPTY_METADATA);
            return;
        }

        Metadata metadata = new Metadata(convertHeadersToArray(req.headers()));

        ServerStreamListener listener = startCall(stream, methodName, method, metadata);
        stream.transportState().setListener(listener);
        req.subscribe(stream.messageReader());
    }

    private <T_I, T_O> ServerStreamListener startCall(ServerStream stream,
                                                      String fullMethodName,
                                                      ServerMethodDefinition<T_I, T_O> methodDef,
                                                      Metadata headers) {
        ServerCallImpl<T_I, T_O> call = new ServerCallImpl<>(
                stream, methodDef.getMethodDescriptor(), headers, decompressorRegistry,
                compressorRegistry);
        ServerCall.Listener<T_I> listener =
                methodDef.getServerCallHandler().startCall(call, headers);
        if (listener == null) {
            throw new NullPointerException(
                    "startCall() returned a null listener for method " + fullMethodName);
        }
        return call.newServerStreamListener(listener);
    }

    private boolean verifyContentType(HttpHeaders headers) throws Http2Exception {
        String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        return contentType != null && GrpcUtil.isGrpcContentType(contentType);
    }

    @Nullable
    private String determineMethod(ServiceRequestContext ctx) throws Http2Exception {
        // Remove the leading slash of the path and get the fully qualified method name
        String path = ctx.mappedPath();
        if (path.charAt(0) != '/') {
            return null;
        }
        return path.substring(1, path.length());
    }

    private byte[][] convertHeadersToArray(HttpHeaders headers) {
        // The Netty AsciiString class is really just a wrapper around a byte[] and supports
        // arbitrary binary data, not just ASCII.
        byte[][] headerValues = new byte[headers.size() * 2][];
        int i = 0;
        for (Map.Entry<AsciiString, String> entry : headers) {
            AsciiString key = entry.getKey();
            headerValues[i++] = key.isEntireArrayUsed() ? key.array() : key.toByteArray();
            headerValues[i++] = entry.getValue().getBytes(StandardCharsets.US_ASCII);
        }
        return TransportFrameUtil.toRawSerializedHeaders(headerValues);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        maxMessageSize = cfg.server().config().defaultMaxRequestLength();
    }
}
