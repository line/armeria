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

package com.linecorp.armeria.spring.actuate;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.common.util.ObjectCollectingUtil.MONO_CLASS;
import static com.linecorp.armeria.spring.actuate.OperationArgumentResolverUtil.acceptHeadersResolver;
import static com.linecorp.armeria.spring.actuate.ReactiveHealthEndpointWebExtensionUtil.handleMaybeReactorResult;
import static com.linecorp.armeria.spring.actuate.WebOperationServiceUtil.namespaceResolver;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.OperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.reactive.AbstractWebFluxEndpointHandlerMapping;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;

/**
 * {@link HttpService} to handle a {@link WebOperation}. Mostly inspired by reactive implementation in
 * {@link AbstractWebFluxEndpointHandlerMapping}.
 */
final class WebOperationService implements HttpService {

    private static final Pattern FILENAME_BAD_CHARS = Pattern.compile("['/\\\\?%*:|\"<> ]");

    private static final Logger logger = LoggerFactory.getLogger(WebOperationService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<Map<String, Object>>() {};

    private static final boolean HAS_PRODUCIBLE_OPERATION_ARGUMENT_RESOLVER;
    static final boolean HAS_WEB_SERVER_NAMESPACE;

    static {
        // WebServerNamespace has been added in Spring Boot 2.6.
        final String webServerNamespaceClassName =
                "org.springframework.boot.actuate.endpoint.web.WebServerNamespace";

        Class<?> webServerNamespaceClass = null;
        try {
            webServerNamespaceClass =
                    Class.forName(webServerNamespaceClassName, false, Health.class.getClassLoader());
        } catch (Throwable cause) {
            logger.debug("Failed to find {} - not Spring Boot 2.6+?",
                         webServerNamespaceClassName, cause);
        }

        HAS_WEB_SERVER_NAMESPACE = webServerNamespaceClass != null;

        // ProducibleOperationArgumentResolver has been added in Spring Boot 2.5.0
        final String producibleOperationArgumentResolverClassName =
                "org.springframework.boot.actuate.endpoint.ProducibleOperationArgumentResolver";
        boolean hasArgumentResolver;
        try {
            Class.forName(producibleOperationArgumentResolverClassName, false,
                          WebOperationService.class.getClassLoader());
            hasArgumentResolver = true;
        } catch (ClassNotFoundException e) {
            hasArgumentResolver = false;
        }
        HAS_PRODUCIBLE_OPERATION_ARGUMENT_RESOLVER = hasArgumentResolver;
    }

    private final WebOperation operation;
    private final HttpCodeStatusMapper statusMapper;
    private final boolean isServerNamespace;
    private final Map<String, Object> additionalArguments;

    WebOperationService(WebOperation operation, HttpCodeStatusMapper statusMapper,
                        boolean isServerNamespace, // management if it's false.
                        Map<String, Object> additionalArguments) {
        this.operation = operation;
        this.statusMapper = statusMapper;
        this.isServerNamespace = isServerNamespace;
        this.additionalArguments = additionalArguments;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        if (operation.isBlocking()) {
            return HttpResponse.of(req.aggregate().thenApplyAsync(invoke(ctx), ctx.blockingTaskExecutor()));
        } else {
            return HttpResponse.of(req.aggregate().thenApply(invoke(ctx)));
        }
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return ExchangeType.UNARY;
    }

    private Function<AggregatedHttpRequest, HttpResponse> invoke(ServiceRequestContext ctx) {
        return req -> {
            final Map<String, Object> arguments = getArguments(ctx, req);
            try {
                final Object result = operation.invoke(newInvocationContext(req, arguments));
                if (MONO_CLASS != null) {
                    return handleMaybeReactorResult(statusMapper, ctx, result, req.method());
                }

                return handleResult(statusMapper, ctx, result, req.method());
            } catch (Throwable throwable) {
                logger.warn("Unexpected exception while invoking {} with {}.",
                            WebOperationService.class.getSimpleName(), arguments, throwable);
                return Exceptions.throwUnsafely(throwable);
            }
        };
    }

    private Map<String, Object> getArguments(ServiceRequestContext ctx, AggregatedHttpRequest req) {
        final Map<String, Object> arguments = new LinkedHashMap<>(additionalArguments.size() +
                                                                  ctx.pathParams().size() + 16);
        arguments.putAll(additionalArguments);
        arguments.putAll(ctx.pathParams());
        if (!req.content().isEmpty()) {
            final Map<String, Object> bodyParams;
            try {
                bodyParams = OBJECT_MAPPER.readValue(req.content().array(), JSON_MAP);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid JSON in request.");
            }
            arguments.putAll(bodyParams);
        }

        final QueryParams params = QueryParams.fromQueryString(ctx.query());
        for (String name : params.names()) {
            final List<String> values = params.getAll(name);
            arguments.put(name, values.size() != 1 ? values : values.get(0));
        }

        return ImmutableMap.copyOf(arguments);
    }

    private InvocationContext newInvocationContext(AggregatedHttpRequest req, Map<String, Object> arguments) {
        if (HAS_PRODUCIBLE_OPERATION_ARGUMENT_RESOLVER) {
            final OperationArgumentResolver acceptHeadersResolver = acceptHeadersResolver(req.headers());
            if (!HAS_WEB_SERVER_NAMESPACE) {
                return new InvocationContext(SecurityContext.NONE, arguments, acceptHeadersResolver);
            }

            return new InvocationContext(SecurityContext.NONE, arguments,
                                         namespaceResolver(isServerNamespace), acceptHeadersResolver);
        }
        return new InvocationContext(SecurityContext.NONE, arguments);
    }

    static HttpResponse handleResult(HttpCodeStatusMapper statusMapper, ServiceRequestContext ctx,
                                     @Nullable Object result, HttpMethod method) throws Throwable {
        if (result == null) {
            return HttpResponse.of(method != HttpMethod.GET ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
        }

        return handleResult0(statusMapper, ctx, result);
    }

    static HttpResponse handleResult0(HttpCodeStatusMapper statusMapper,
                                      ServiceRequestContext ctx, Object result) throws Throwable {
        final HttpStatus status;
        final Object body;
        MediaType contentType = null;
        if (result instanceof WebEndpointResponse) {
            final WebEndpointResponse<?> webResult = (WebEndpointResponse<?>) result;
            status = HttpStatus.valueOf(webResult.getStatus());
            body = webResult.getBody();
            contentType = toMediaType(webResult.getContentType());
        } else {
            if (result instanceof Health) {
                status = HttpStatus.valueOf(statusMapper.getStatusCode(((Health) result).getStatus()));
            } else if (result instanceof HealthComponent) {
                final Status actuatorStatus = ((HealthComponent) result).getStatus();
                status = HttpStatus.valueOf(statusMapper.getStatusCode(actuatorStatus));
            } else {
                status = HttpStatus.OK;
            }
            body = result;
        }

        if (contentType == null) {
            contentType = firstNonNull(ctx.negotiatedResponseMediaType(), MediaType.JSON_UTF_8);
        }

        if (contentType.isJson()) {
            final ResponseHeaders headers = ResponseHeaders.builder(status)
                                                           .contentType(contentType)
                                                           .build();
            return HttpResponse.ofJson(headers, body);
        }

        if (body instanceof CharSequence) {
            return HttpResponse.of(status, contentType, (CharSequence) body);
        }

        if (body instanceof byte[]) {
            return HttpResponse.of(status, contentType, (byte[]) body);
        }

        if (body instanceof Resource) {
            final Resource resource = (Resource) body;
            final String filename = resource.getFilename();
            final HttpResponseWriter res = HttpResponse.streaming();
            final long length = resource.contentLength();
            final ResponseHeadersBuilder headers = ResponseHeaders.builder(status);
            headers.contentType(contentType);
            headers.contentLength(length);
            headers.setTimeMillis(HttpHeaderNames.LAST_MODIFIED, resource.lastModified());
            if (filename != null) {
                headers.set(HttpHeaderNames.CONTENT_DISPOSITION,
                            "attachment;filename=" + FILENAME_BAD_CHARS.matcher(filename).replaceAll("_"));
            }

            res.write(headers.build());

            boolean success = false;
            ReadableByteChannel in = null;
            try {
                in = resource.readableChannel();
                final ReadableByteChannel finalIn = in;
                ctx.blockingTaskExecutor().execute(() -> streamResource(ctx, res, finalIn, length));
                success = true;
                return res;
            } finally {
                if (!success && in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn("{} Failed to close an actuator resource: {}", ctx, resource, e);
                    }
                }
            }
        }

        logger.warn("{} Cannot convert an actuator response: {}", ctx, body);
        return HttpResponse.of(status, contentType, body.toString());
    }

    @Nullable
    static MediaType toMediaType(@Nullable MimeType mimeType) {
        if (mimeType == null) {
            return null;
        }
        final MediaType mediaType = MediaType.create(mimeType.getType(), mimeType.getSubtype());
        final Map<String, String> parameters = mimeType.getParameters();
        if (parameters.isEmpty()) {
            return mediaType;
        }
        return mediaType.withParameters(
                parameters.entrySet()
                          .stream()
                          .collect(toImmutableMap(Map.Entry::getKey,
                                                  entry -> ImmutableSet.of(entry.getValue()))));
    }

    // TODO(trustin): A lot of duplication with StreamingHttpFile. Need to add some utility classes for
    //                streaming a ReadableByteChannel and an InputStream.
    private static void streamResource(ServiceRequestContext ctx, HttpResponseWriter res,
                                       ReadableByteChannel in, long remainingBytes) {

        final int chunkSize = (int) Math.min(8192, remainingBytes);
        final ByteBuf buf = ctx.alloc().buffer(chunkSize);
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

        final long nextRemainingBytes = remainingBytes - readBytes;
        final boolean endOfStream = nextRemainingBytes == 0;
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
                ctx.blockingTaskExecutor()
                   .execute(() -> streamResource(ctx, res, in, nextRemainingBytes));
            } catch (Exception e) {
                close(res, in, e);
            }
        });
    }

    private static int read(ReadableByteChannel src, ByteBuf dst) throws IOException {
        if (src instanceof ScatteringByteChannel) {
            return dst.writeBytes((ScatteringByteChannel) src, dst.writableBytes());
        }

        final int readBytes = src.read(dst.nioBuffer(dst.writerIndex(), dst.writableBytes()));
        if (readBytes > 0) {
            dst.writerIndex(dst.writerIndex() + readBytes);
        }
        return readBytes;
    }

    private static void close(HttpResponseWriter res, Closeable in) {
        close(in);
        res.close();
    }

    private static void close(HttpResponseWriter res, Closeable in, Exception cause) {
        close(in);
        res.close(cause);
    }

    private static void close(Closeable in) {
        try {
            in.close();
        } catch (Exception e) {
            logger.warn("Failed to close a stream for: {}", in, e);
        }
    }
}
