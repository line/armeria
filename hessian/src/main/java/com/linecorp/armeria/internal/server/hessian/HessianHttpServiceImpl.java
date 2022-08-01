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

package com.linecorp.armeria.internal.server.hessian;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caucho.hessian.io.AbstractHessianInput;
import com.caucho.hessian.io.AbstractHessianOutput;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.SerializerFactory;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.hessian.HessianFaultException;
import com.linecorp.armeria.internal.common.hessian.HessianMethod;
import com.linecorp.armeria.internal.common.hessian.HessianNoSuchMethodException;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.hessian.HessianHttpService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;

/**
 * An {@link HttpService} that handles a Hessian method call.
 *
 */
public final class HessianHttpServiceImpl
        extends DecoratingService<RpcRequest, RpcResponse, HttpRequest, HttpResponse>
        implements HessianHttpService {

    private static final Logger logger = LoggerFactory.getLogger(HessianHttpServiceImpl.class);

    private static final String PROTOCOL_NOT_SUPPORTED = "Specified content-type not supported";

    private static final String ACCEPT_HESSIAN_PROTOCOL_MUST_MATCH_CONTENT_TYPE =
            "HESSIAN protocol specified in Accept header must match " +
            "the one specified in the content-type header";

    private final HessianCallService hessianService;

    private final SerializationFormat defaultSerializationFormat;

    private final BiFunction<? super ServiceRequestContext, ? super Throwable, @Nullable ? extends RpcResponse>
            exceptionHandler;

    private final Set<Route> routes;

    private final SerializerFactory serializerFactory;

    public HessianHttpServiceImpl(RpcService delegate, SerializationFormat defaultSerializationFormat,
                                  BiFunction<? super ServiceRequestContext,
                                          ? super Throwable, ? extends RpcResponse> exceptionHandler) {
        super(delegate);
        hessianService = findHessianService(delegate);
        this.defaultSerializationFormat = defaultSerializationFormat;
        this.exceptionHandler = exceptionHandler;
        routes = hessianService.getHessianServices().keySet().stream().map(
                                       it -> Route.builder().path(it).build())
                               .collect(Collectors.toSet());
        serializerFactory = new SerializerFactory(Thread.currentThread().getContextClassLoader());
    }

    private static HessianCallService findHessianService(
            Service<?, ?> delegate) {
        @Nullable
        final HessianCallService hessianService = delegate.as(HessianCallService.class);
        checkState(hessianService != null, "service being decorated is not a hessianService: %s", delegate);
        return hessianService;
    }

    /**
     * Returns the default {@link SerializationFormat} of this service.
     */
    public SerializationFormat defaultSerializationFormat() {
        return defaultSerializationFormat;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Nullable
        final SerializationFormat serializationFormat = determineSerializationFormat(req);
        if (serializationFormat == null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                   PROTOCOL_NOT_SUPPORTED);
        }

        if (!validateAcceptHeaders(req, serializationFormat)) {
            return HttpResponse.of(HttpStatus.NOT_ACCEPTABLE, MediaType.PLAIN_TEXT_UTF_8,
                                   ACCEPT_HESSIAN_PROTOCOL_MUST_MATCH_CONTENT_TYPE);
        }

        if (hessianService.hessianServiceMetadataOfPath(ctx.mappedPath()) == null) {
            // should not reach here.
            logger.error("{} not found. But should not reach here.", req.path()); //NOSONAR
            return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.PLAIN_TEXT_UTF_8,
                                   req.path() + " not found.");
        }

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture);
        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT);
        req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((aReq, cause) -> {
            if (cause != null) {
                final HttpResponse errorRes;
                if (ctx.config().verboseResponses()) {
                    errorRes = HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                                               Exceptions.traceText(cause));
                } else {
                    errorRes = HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                responseFuture.complete(errorRes);
                return null;
            }
            decodeAndInvoke(ctx, aReq, serializationFormat, responseFuture);
            return null;
        }).exceptionally(CompletionActions::log);
        return res;
    }

    @Nullable
    private SerializationFormat determineSerializationFormat(HttpRequest req) {
        final HttpHeaders headers = req.headers();
        @Nullable
        final MediaType contentType = headers.contentType();

        if (contentType != null) {
            if (defaultSerializationFormat.isAccepted(contentType)) {
                return defaultSerializationFormat();
            } else {
                return null;
            }
        }

        return defaultSerializationFormat();
    }

    private static boolean validateAcceptHeaders(HttpRequest req, SerializationFormat serializationFormat) {
        // If accept header is present, make sure it is sane.
        final List<String> acceptHeaders = req.headers().getAll(HttpHeaderNames.ACCEPT);
        return acceptHeaders.isEmpty() || serializationFormat.mediaTypes().matchHeaders(acceptHeaders) != null;
    }

    private void decodeAndInvoke(ServiceRequestContext ctx, AggregatedHttpRequest req,
                                 SerializationFormat serializationFormat,
                                 CompletableFuture<HttpResponse> httpRes) {

        final RpcRequest decodedReq;
        @Nullable
        final HessianServiceMetadata metadata = hessianService.hessianServiceMetadataOfPath(ctx.mappedPath());
        assert metadata != null;
        AbstractHessianInput in = null;
        final HeaderType headerType;
        try (HttpData content = req.content()) {
            final InputStream isToUse = content.toInputStream();

            // step1: read header
            try {
                headerType = readHeaderType(isToUse);
            } catch (Exception e) {
                logger.debug("{} Failed to decode a {} header:", ctx, serializationFormat, e);

                // not hessian request. response as txt.
                final HttpResponse errorRes;
                if (ctx.config().verboseResponses()) {
                    errorRes = HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                               "Failed to decode a %s header: %s", serializationFormat,
                                               Exceptions.traceText(e));
                } else {
                    errorRes = HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8,
                                               "Failed to decode a %s header", serializationFormat);
                }

                httpRes.complete(errorRes);
                return;
            }

            // step2: read input
            // Decode the request.
            @Nullable String methodName = null;
            try {
                if (headerType.isCall2()) {
                    in = new Hessian2Input(isToUse);
                    in.readCall();
                } else {
                    in = new HessianInput(isToUse);
                }
                in.setSerializerFactory(serializerFactory);
                in.skipOptionalCall();

                methodName = in.readMethod();
                final int argLength = in.readMethodArgLength();

                if ("_hessian_getAttribute".equals(methodName)) {
                    final String attrName = in.readString();
                    in.completeCall();
                    decodedReq = new HessianRpcRequest(metadata.serviceType(), null, methodName, metadata,
                                                       attrName);
                } else {
                    @Nullable
                    final HessianMethod hessianMethod = metadata.method(methodName);
                    if (hessianMethod == null) {
                        throw new HessianNoSuchMethodException(
                                "The service has no method named: " + methodName);
                    }
                    final Class<?>[] args = hessianMethod.getMethod().getParameterTypes();

                    if (argLength != args.length && argLength >= 0) {
                        throw new HessianNoSuchMethodException(
                                "The service has no method named: " + methodName + " with length " + argLength,
                                "NoSuchMethod");
                    }
                    final Object[] values = new Object[args.length];

                    for (int i = 0; i < args.length; i++) {
                        values[i] = in.readObject(args[i]);
                    }

                    decodedReq = new HessianRpcRequest(metadata.serviceType(), null, methodName, metadata,
                                                       values);
                }
                ctx.logBuilder().requestContent(decodedReq, null);
            } catch (Exception e) {
                // Failed to decode the invocation parameters.
                logger.info("{} Failed to decode Hessian request:", ctx, e);
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ex) {
                        logger.error("close hessian input error:", ex);
                    }
                }
                handlePreDecodeException(ctx, httpRes, e, serializationFormat, headerType, serializerFactory);
                return;
            }
        } finally {
            ctx.logBuilder().requestContent(null, null);
        }

        invoke(ctx, serializationFormat, decodedReq, httpRes, in, headerType);
    }

    // read the header.
    private static HeaderType readHeaderType(InputStream isToUse) throws IOException {
        final int code = isToUse.read();
        final int major = isToUse.read();
        final int minor = isToUse.read();

        if (code == 'H') {
            // Hessian 2.0 stream
            if (major != 0x02) {
                throw new IOException("Version " + major + '.' + minor + " is not understood");
            }
            return HeaderType.HESSIAN_2;
        } else if (code == 'C') {
            // Hessian 2.0 call... for some reason not handled in HessianServlet!
            isToUse.reset();
            return HeaderType.HESSIAN_2;
        } else if (code == 'c') {
            // Hessian 1.0 call
            if (major >= 2) {
                return HeaderType.CALL_1_REPLY_2;
            } else {
                return HeaderType.CALL_1_REPLY_1;
            }
        } else {
            throw new IOException(
                    "Expected 'H'/'C' (Hessian 2.0) or 'c' (Hessian 1.0) in hessian input at " + code);
        }
    }

    private void invoke(ServiceRequestContext ctx, SerializationFormat serializationFormat, RpcRequest call,
                        CompletableFuture<HttpResponse> res, @Nullable AbstractHessianInput hessianInput,
                        HeaderType headerType) {

        final RpcResponse reply;

        try (SafeCloseable ignored = ctx.push()) {
            reply = unwrap().serve(ctx, call);
        } catch (Throwable cause) {
            if (hessianInput != null) {
                try {
                    hessianInput.close();
                } catch (IOException ex) {
                    logger.error("Close hessian input error:", ex);
                }
            }
            handleException(ctx, res, serializationFormat, headerType, cause);
            return;
        }

        reply.handle((result, cause) -> {

            if (hessianInput != null) {
                try {
                    hessianInput.close();
                } catch (IOException ex) {
                    logger.error("close hessian input error:", ex);
                }
            }

            if (cause != null) {
                handleException(ctx, res, serializationFormat, headerType, cause);
                return null;
            }

            try {
                handleSuccess(ctx, res, serializationFormat, headerType, serializerFactory, result);
            } catch (Throwable t) {
                handleException(ctx, res, serializationFormat, headerType, t);
                return null;
            }

            return null;
        }).exceptionally(CompletionActions::log);
    }

    private static void handleSuccess(ServiceRequestContext ctx,
                                      CompletableFuture<HttpResponse> httpRes,
                                      SerializationFormat serializationFormat, HeaderType headerType,
                                      SerializerFactory serializerFactory,
                                      Object returnValue) {
        respond(serializationFormat,
                encodeSuccess(ctx, headerType, serializerFactory,
                              returnValue),
                httpRes);
    }

    private void handleException(ServiceRequestContext ctx, CompletableFuture<HttpResponse> res,
                                 SerializationFormat serializationFormat, HeaderType headerType,
                                 Throwable cause) {
        final RpcResponse response = handleException(ctx, cause);
        response.handle((result, convertedCause) -> {
            if (convertedCause != null) {
                handleException(ctx, res, serializationFormat, headerType, serializerFactory,
                                convertedCause);
            } else {
                handleSuccess(ctx, res, serializationFormat, headerType, serializerFactory,
                              result);
            }
            return null;
        });
    }

    // may convert exception to success response.
    private RpcResponse handleException(ServiceRequestContext ctx, Throwable cause) {
        @Nullable
        final RpcResponse res = exceptionHandler.apply(ctx, cause);
        if (res == null) {
            logger.warn("exceptionHandler.apply() returned null.");
            return RpcResponse.ofFailure(cause);
        }
        return res;
    }

    private static void handleException(ServiceRequestContext ctx,
                                        CompletableFuture<HttpResponse> httpRes,
                                        SerializationFormat serializationFormat, HeaderType headerType,
                                        SerializerFactory serializerFactory,
                                        Throwable cause) {

        if (cause instanceof HttpStatusException) {
            httpRes.complete(HttpResponse.of(((HttpStatusException) cause).httpStatus()));
            return;
        }

        if (cause instanceof HttpResponseException) {
            httpRes.complete(((HttpResponseException) cause).httpResponse());
            return;
        }

        final HttpData content;
        try {
            content = encodeException(ctx, headerType, serializerFactory,
                                      cause);
            respond(serializationFormat, content, httpRes);
        } catch (Throwable throwable) {
            httpRes.complete(HttpResponse.ofFailure(throwable));
        }
    }

    private static void handlePreDecodeException(ServiceRequestContext ctx,
                                                 CompletableFuture<HttpResponse> httpRes,
                                                 Throwable cause, SerializationFormat serializationFormat,
                                                 HeaderType headerType,
                                                 SerializerFactory serializerFactory) {

        final HttpData content = encodeException(ctx,
                                                 headerType,
                                                 serializerFactory, cause);
        respond(serializationFormat, content, httpRes);
    }

    private static void respond(SerializationFormat serializationFormat, HttpData content,
                                CompletableFuture<HttpResponse> res) {
        res.complete(HttpResponse.of(HttpStatus.OK, serializationFormat.mediaType(), content));
    }

    private static HttpData encodeSuccess(ServiceRequestContext ctx,
                                          HeaderType headerType,
                                          SerializerFactory serializerFactory,
                                          Object value) {

        final ByteBuf buf = ctx.alloc().buffer(128);
        boolean success = false;
        final AbstractHessianOutput hessianOutput;
        try {
            hessianOutput = hessianOutput(buf, headerType, serializerFactory);
            hessianOutput.writeReply(value);
            // not need to close.
            hessianOutput.flush();
            final HttpData encoded = HttpData.wrap(buf);
            success = true;
            return encoded;
        } catch (IOException e) { // NOSONAR
            logger.error("Write hessian response error.", e);
            throw new IllegalStateException("Write hessian response error", e);
        } finally {
            if (!success) {
                buf.release();
            }
        }
    }

    private static HttpData encodeException(ServiceRequestContext ctx,
                                            HeaderType headerType,
                                            SerializerFactory serializerFactory,
                                            Throwable cause) {

        Throwable exception = cause;
        if (exception instanceof InvocationTargetException) {
            exception = ((InvocationTargetException) cause).getTargetException();
        }

        logger.info("encode exception.", exception);

        final ByteBuf buf = ctx.alloc().buffer(128);
        boolean success = false;

        final AbstractHessianOutput out;
        try {
            out = hessianOutput(buf, headerType, serializerFactory);
            if (exception instanceof HessianFaultException) {
                final HessianFaultException hfe = (HessianFaultException) exception;
                out.writeFault(hfe.getCode(), escapeMessage(hfe.getMessage()), hfe.getDetail());
            } else {
                out.writeFault("ServiceException", escapeMessage(exception.getMessage()), exception);
            }
            // flush, not close.
            out.flush();
            final HttpData encoded = HttpData.wrap(buf);
            success = true;
            return encoded;
        } catch (IOException e) { // NOSONAR
            logger.error("write hessian error response failed:", e);
            throw new IllegalStateException("Write error response failed.", e);
        } finally {
            if (!success) {
                buf.release();
            }
        }
    }

    private static AbstractHessianOutput hessianOutput(ByteBuf buf, HeaderType headerType,
                                                       SerializerFactory serializerFactory) {
        final AbstractHessianOutput output;
        if (headerType.isReply2()) {
            final Hessian2Output output2 = new Hessian2Output(new ByteBufOutputStream(buf));
            // not close ByteBuf
            output2.setCloseStreamOnClose(false);
            output = output2;
        } else {
            // hessian1
            output = new HessianOutput(new ByteBufOutputStream(buf));
        }
        output.setSerializerFactory(serializerFactory);
        return output;
    }

    @Override
    public Set<Route> routes() {
        return routes;
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, Route route) {
        return true;
    }

    // copy from Hessian
    @Nullable
    static String escapeMessage(@Nullable String msg) {
        if (msg == null) {
            return null;
        }

        final StringBuilder sb = new StringBuilder();

        final int length = msg.length();
        for (int i = 0; i < length; i++) {
            final char ch = msg.charAt(i);

            switch (ch) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case 0x0:
                    sb.append("&#00;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                default:
                    sb.append(ch);
                    break;
            }
        }

        return sb.toString();
    }
}
