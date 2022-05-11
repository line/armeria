/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.client.hessian;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caucho.hessian.HessianException;
import com.caucho.hessian.client.HessianConnectionException;
import com.caucho.hessian.client.HessianRuntimeException;
import com.caucho.hessian.io.AbstractHessianInput;
import com.caucho.hessian.io.AbstractHessianOutput;
import com.caucho.hessian.io.HessianProtocolException;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingClient;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.hessian.HessianClientOptions;
import com.linecorp.armeria.common.CompletableRpcResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.hessian.HessianFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;

/**
 * 实现rpc的http调用.
 *
 */
final class HessianHttpClientDelegate
        extends DecoratingClient<HttpRequest, HttpResponse, RpcRequest, RpcResponse>
        implements RpcClient {

    private static final Logger log = LoggerFactory.getLogger(HessianHttpClientDelegate.class);

    private final SerializationFormat serializationFormat;

    private final MediaType mediaType;

    private final Map<Class<?>, HessianServiceClientMetadata> metadataMap = new ConcurrentHashMap<>();

    private final HessianClientFactory factory;

    private final ClientOptions options;

    HessianHttpClientDelegate(HttpClient httpClient, SerializationFormat serializationFormat,
                              HessianClientFactory factory, ClientOptions options) {
        super(httpClient);
        this.serializationFormat = serializationFormat;
        this.factory = factory;
        mediaType = serializationFormat.mediaType();
        this.options = options;
    }

    private boolean isOverloadEnabled() {
        return options.get(HessianClientOptions.OVERLOAD_ENABLED);
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest call) {
        final String method = call.method();
        final List<Object> args = call.params();
        final CompletableRpcResponse reply = new CompletableRpcResponse();

        ctx.logBuilder().serializationFormat(serializationFormat);

        @Nullable
        final HessianFunction func;
        try {
            func = metadata(call.serviceType()).function(method);
            if (func == null) {
                throw new IllegalArgumentException("Hessian method not found: " + method);
            }
        } catch (Throwable cause) {
            // not call hessian, need cast to hessian exceptions.
            reply.completeExceptionally(cause);
            return reply;
        }
        try {

            final ByteBuf buf = ctx.alloc().buffer(128);

            try {
                try (ByteBufOutputStream os = new ByteBufOutputStream(buf)) {
                  final  AbstractHessianOutput out = factory.getHessianOutput(os, options);
                    out.call(method, args.toArray());
                    out.flush();
                }
                ctx.logBuilder().requestContent(call, null);
            } catch (Throwable t) {
                buf.release();
                throw castException(t);
            }

            @Nullable
            final Endpoint endpoint = ctx.endpoint();

            final HttpRequest httpReq =
                    HttpRequest.of(RequestHeaders.builder(HttpMethod.POST, ctx.path())
                                                 .scheme(ctx.sessionProtocol()).authority(
                                                   endpoint != null ? endpoint.authority() : "UNKNOWN")
                                                 .contentType(mediaType).build(),
                                   HttpData.wrap(buf).withEndOfStream());

            ctx.updateRequest(httpReq);
            ctx.logBuilder().defer(RequestLogProperty.RESPONSE_CONTENT);

            final HttpResponse httpResponse;
            try {
                httpResponse = unwrap().execute(ctx, httpReq);
            } catch (Throwable t) {
                httpReq.abort();
                throw castException(t);
            }

            httpResponse.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((res, cause) -> {
                if (cause != null) {
                    handlePreDecodeException(ctx, reply, Exceptions.peel(cause));
                    return null;
                }

                try (HttpData content = res.content()) {
                    final HttpStatus status = res.status();
                    if (status.code() != HttpStatus.OK.code()) {
                        handlePreDecodeException(ctx, reply,
                                                 new InvalidResponseHeadersException(res.headers()));
                        return null;
                    }

                    try {
                        handle(ctx, reply, func, content);
                    } catch (Throwable t) {
                        handlePreDecodeException(ctx, reply, t);
                    }
                }

                return null;
            }).exceptionally(CompletionActions::log);
        } catch (Throwable cause) {
            handlePreDecodeException(ctx, reply, cause);
        }

        return reply;
    }

    private HessianServiceClientMetadata metadata(Class<?> serviceType) {
        final HessianServiceClientMetadata metadata = metadataMap.get(serviceType);
        if (metadata != null) {
            return metadata;
        }

        return metadataMap.computeIfAbsent(serviceType,
                                           type -> new HessianServiceClientMetadata(type, isOverloadEnabled()));
    }

    private void handle(ClientRequestContext ctx, CompletableRpcResponse reply, HessianFunction func,
                        HttpData content) throws Throwable {

        try {
            if (content.isEmpty()) {
                throw new HessianProtocolException("Missing result");
            }

           final AbstractHessianInput in;
            try (InputStream is = content.toInputStream()) {
                Object value;
                final int code = is.read();
                if (code == 'H') {
                    final int major = is.read();
                    final int minor = is.read();

                    in = factory.getHessian2Input(is, options);

                    value = in.readReply(func.getReturnType());

                    if (value instanceof InputStream) {
                        value = new ResultInputStream(is, in, (InputStream) value);
                    }
                } else if (code == 'r') {
                    final int major = is.read();
                    final int minor = is.read();

                    in = factory.getHessianInput(is, options);

                    in.startReplyBody();

                    value = in.readObject(func.getReturnType());

                    if (value instanceof InputStream) {
                        value = new ResultInputStream(is, in, (InputStream) value);
                    } else {
                        in.completeReply();
                    }
                } else {
                    throw new HessianProtocolException("'" + (char) code + "' is an unknown code");
                }
                handleSuccess(ctx, reply, value, null);
            }
        } catch (HessianProtocolException ex) {
            handleException(ctx, reply, null, new HessianRuntimeException(ex));
        }
    }

    private static void handleSuccess(ClientRequestContext ctx, CompletableRpcResponse reply,
                                      @Nullable Object returnValue, @Nullable Object rawResponseContent) {
        reply.complete(returnValue);
        ctx.logBuilder().responseContent(reply, rawResponseContent);
    }

    private static void handleException(ClientRequestContext ctx, CompletableRpcResponse reply,
                                        @Nullable Object rawResponseContent, Exception cause) {
        reply.completeExceptionally(cause);
        ctx.logBuilder().responseContent(reply, rawResponseContent);
    }

    private static void handlePreDecodeException(ClientRequestContext ctx, CompletableRpcResponse reply,
                                                 Throwable cause) {
        handleException(ctx, reply, null, castException(cause));
    }

    private static Exception castException(Throwable cause) {
        if (cause instanceof HessianRuntimeException || cause instanceof HessianException) {
            return (Exception) cause;
        }
        if (cause instanceof InvalidResponseHeadersException) {
         final    InvalidResponseHeadersException exception = (InvalidResponseHeadersException) cause;
         final    String code = exception.headers().status().codeAsText();
            // we do not set InvalidResponseHeadersException as rootCause here.
            return new HessianConnectionException("status code: " + code,
                                                  new RuntimeException(Exceptions.traceText(cause)));
        }
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new HessianRuntimeException(cause);
    }

    /**
     * Hessian service返回InputStream时返回这个.
     */
    static class ResultInputStream extends InputStream {

        @Nullable
        private InputStream connIs;

        @Nullable
        private AbstractHessianInput hessianInput;

        @Nullable
        private InputStream hessianIs;

        ResultInputStream(@Nonnull InputStream is, @Nonnull AbstractHessianInput hessianInput,
                          @Nonnull InputStream hessianIs) {
            connIs = is;
            this.hessianInput = hessianInput;
            this.hessianIs = hessianIs;
        }

        @Override
        public int read() throws IOException {
            if (hessianIs != null) {
             final    int value = hessianIs.read();

                if (value < 0) {
                    close();
                }

                return value;
            } else {
                return -1;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (hessianIs != null) {
             final    int value = hessianIs.read(buffer, offset, length);

                if (value < 0) {
                    close();
                }

                return value;
            } else {
                return -1;
            }
        }

        @Override
        public void close() {

            @Nullable
            final InputStream connIsTemp = this.connIs;
            this.connIs = null;

            @Nullable
            final AbstractHessianInput in = this.hessianInput;
            this.hessianInput = null;

            @Nullable
            final InputStream hessianIsTemp = this.hessianIs;
            this.hessianIs = null;

            try {
                if (hessianIsTemp != null) {
                    hessianIsTemp.close();
                }
            } catch (Exception e) {
                log.debug(e.toString(), e);
            }

            try {
                if (in != null) {
                    in.completeReply();
                    in.close();
                }
            } catch (Exception e) {
                log.debug(e.toString(), e);
            }

            try {
                if (connIsTemp != null) {
                    connIsTemp.close();
                }
            } catch (Exception e) {
                log.debug(e.toString(), e);
            }
        }
    }
}
