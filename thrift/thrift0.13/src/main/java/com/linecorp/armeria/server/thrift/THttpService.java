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

package com.linecorp.armeria.server.thrift;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
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
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.thrift.TByteBufTransport;
import com.linecorp.armeria.internal.common.thrift.ThriftFieldAccess;
import com.linecorp.armeria.internal.common.thrift.ThriftFunction;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;
import com.linecorp.armeria.internal.common.thrift.ThriftProtocolUtil;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil.DecoratorAndOrder;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.VirtualHost;

import io.netty.buffer.ByteBuf;
import io.netty.util.AttributeKey;

/**
 * An {@link HttpService} that handles a Thrift call.
 *
 * @see ThriftProtocolFactories
 */
public final class THttpService extends DecoratingService<RpcRequest, RpcResponse, HttpRequest, HttpResponse>
        implements HttpService {

    private static final AttributeKey<DecodedRequest> DECODED_REQUEST =
            AttributeKey.valueOf(THttpService.class, "DECODED_REQUEST");

    private static final Logger logger = LoggerFactory.getLogger(THttpService.class);

    private static final String PROTOCOL_NOT_SUPPORTED = "Specified content-type not supported";

    private static final String ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE =
            "Thrift protocol specified in Accept header must match " +
            "the one specified in the content-type header";

    /**
     * Creates a new instance of {@link THttpServiceBuilder} which can build an instance of {@link THttpService}
     * fluently.
     *
     * <p>The default SerializationFormat {@link ThriftSerializationFormats#BINARY} will be used when client
     * does not specify one in the request, but also supports {@link ThriftSerializationFormats#values()}.
     * </p>
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     */
    public static THttpServiceBuilder builder() {
        return new THttpServiceBuilder();
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting all thrift
     * protocols and defaulting to {@link ThriftSerializationFormats#BINARY TBinary} protocol when the client
     * doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     */
    public static THttpService of(Object implementation) {
        return of(implementation, ThriftSerializationFormats.BINARY);
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting all thrift
     * protocols and defaulting to the specified {@code defaultSerializationFormat} when the client doesn't
     * specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     */
    public static THttpService of(Object implementation,
                                  SerializationFormat defaultSerializationFormat) {
        return builder().addService(implementation)
                        .defaultSerializationFormat(defaultSerializationFormat)
                        .build();
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting only the
     * formats specified and defaulting to the specified {@code defaultSerializationFormat} when the client
     * doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the {@code "Content-Type"} header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherSupportedSerializationFormats other serialization formats that should be supported by this
     *                                           service in addition to the default
     */
    public static THttpService ofFormats(
            Object implementation,
            SerializationFormat defaultSerializationFormat,
            SerializationFormat... otherSupportedSerializationFormats) {

        requireNonNull(otherSupportedSerializationFormats, "otherSupportedSerializationFormats");
        return ofFormats(implementation,
                         defaultSerializationFormat,
                         Arrays.asList(otherSupportedSerializationFormats));
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting the protocols
     * specified in {@code otherSupportedSerializationFormats} and defaulting to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the {@code "Content-Type"} header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherSupportedSerializationFormats other serialization formats that should be supported by this
     *                                           service in addition to the default
     */
    public static THttpService ofFormats(
            Object implementation,
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherSupportedSerializationFormats) {
        return builder().addService(implementation)
                        .defaultSerializationFormat(defaultSerializationFormat)
                        .otherSerializationFormats(otherSupportedSerializationFormats)
                        .build();
    }

    /**
     * Creates a new decorator that supports all thrift protocols and defaults to
     * {@link ThriftSerializationFormats#BINARY TBinary} protocol when the client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     */
    public static Function<? super RpcService, THttpService> newDecorator() {
        return newDecorator(ThriftSerializationFormats.BINARY);
    }

    /**
     * Creates a new decorator that supports all thrift protocols and defaults to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     * Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     *
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     */
    public static Function<? super RpcService, THttpService> newDecorator(
            SerializationFormat defaultSerializationFormat) {
        return builder().defaultSerializationFormat(defaultSerializationFormat).newDecorator();
    }

    /**
     * Creates a new decorator that supports only the formats specified and defaults to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     * Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the {@code "Content-Type"} header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherSupportedSerializationFormats other serialization formats that should be supported by this
     *                                           service in addition to the default
     */
    public static Function<? super RpcService, THttpService> newDecorator(
            SerializationFormat defaultSerializationFormat,
            SerializationFormat... otherSupportedSerializationFormats) {
        requireNonNull(otherSupportedSerializationFormats, "otherSupportedSerializationFormats");
        return newDecorator(defaultSerializationFormat,
                            ImmutableSet.copyOf(otherSupportedSerializationFormats));
    }

    /**
     * Creates a new decorator that supports the protocols specified in
     * {@code otherSupportedSerializationFormats} and defaults to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     * Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the {@code "Content-Type"} header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherSupportedSerializationFormats other serialization formats that should be supported by this
     *                                           service in addition to the default
     */
    public static Function<? super RpcService, THttpService> newDecorator(
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherSupportedSerializationFormats) {
        return builder().defaultSerializationFormat(defaultSerializationFormat)
                        .otherSerializationFormats(otherSupportedSerializationFormats)
                        .newDecorator();
    }

    private final ThriftCallService thriftService;
    private final SerializationFormat defaultSerializationFormat;
    private final Set<SerializationFormat> supportedSerializationFormats;
    private final BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
            exceptionHandler;

    private int maxRequestStringLength;
    private int maxRequestContainerLength;
    private final Map<SerializationFormat, TProtocolFactory> responseProtocolFactories;
    private Map<SerializationFormat, TProtocolFactory> requestProtocolFactories;
    private Map<ThriftFunction, HttpService> decoratedTHttpServices;

    @Nullable
    private VirtualHost defaultVirtualHost;

    THttpService(RpcService delegate, SerializationFormat defaultSerializationFormat,
                 Set<SerializationFormat> supportedSerializationFormats,
                 int maxRequestStringLength, int maxRequestContainerLength,
                 BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
                         exceptionHandler) {
        super(delegate);
        thriftService = findThriftService(delegate);
        this.defaultSerializationFormat = defaultSerializationFormat;
        this.supportedSerializationFormats = ImmutableSet.copyOf(supportedSerializationFormats);
        this.maxRequestStringLength = maxRequestStringLength;
        this.maxRequestContainerLength = maxRequestContainerLength;
        this.exceptionHandler = exceptionHandler;
        responseProtocolFactories = supportedSerializationFormats
                .stream()
                .collect(toImmutableMap(
                        Function.identity(),
                        format -> ThriftSerializationFormats.protocolFactory(format, 0, 0)));
        // The actual requestProtocolFactories will be set when this service is added.
        requestProtocolFactories = responseProtocolFactories;
        // The actual decoratedTHttpServices will be set when this service is added.
        decoratedTHttpServices = ImmutableMap.of();
    }

    private static ThriftCallService findThriftService(Service<?, ?> delegate) {
        final ThriftCallService thriftService = delegate.as(ThriftCallService.class);
        checkState(thriftService != null,
                   "service being decorated is not a ThriftCallService: %s", delegate);
        return thriftService;
    }

    /**
     * Returns the information about the Thrift services being served.
     *
     * @return a {@link Map} whose key is a service name, which could be an empty string if this service
     *         is not multiplexed
     */
    public Map<String, ThriftServiceEntry> entries() {
        return thriftService.entries();
    }

    /**
     * Returns the {@link SerializationFormat}s supported by this service.
     */
    public Set<SerializationFormat> supportedSerializationFormats() {
        return supportedSerializationFormats;
    }

    /**
     * Returns the default {@link SerializationFormat} of this service.
     */
    public SerializationFormat defaultSerializationFormat() {
        return defaultSerializationFormat;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        final VirtualHost defaultVirtualHost = cfg.server().config().defaultVirtualHost();
        if (this.defaultVirtualHost == defaultVirtualHost) {
            // Avoid infinite loop. The delegate of annotated decorators is this.
            return;
        }
        this.defaultVirtualHost = defaultVirtualHost;

        if (maxRequestStringLength == -1) {
            maxRequestStringLength = Ints.saturatedCast(cfg.maxRequestLength());
        }
        if (maxRequestContainerLength == -1) {
            maxRequestContainerLength = Ints.saturatedCast(cfg.maxRequestLength());
        }
        requestProtocolFactories = supportedSerializationFormats
                .stream()
                .collect(toImmutableMap(
                        Function.identity(),
                        format -> ThriftSerializationFormats.protocolFactory(
                                format, maxRequestStringLength, maxRequestContainerLength)));

        super.serviceAdded(cfg);

        final DependencyInjector dependencyInjector = cfg.server().config().dependencyInjector();
        final Map<ThriftFunction, HttpService> decoratedTHttpServices = new IdentityHashMap<>();
        for (ThriftServiceEntry thriftServiceEntry : entries().values()) {
            for (ThriftFunction thriftFunction : thriftServiceEntry.metadata.functions().values()) {
                if (!thriftFunction.declaredDecorators().isEmpty()) {
                    final List<DecoratorAndOrder> decorators = thriftFunction.declaredDecorators();
                    Function<? super HttpService, ? extends HttpService> decorator = Function.identity();
                    for (int i = decorators.size() - 1; i >= 0; i--) {
                        final DecoratorAndOrder d = decorators.get(i);
                        decorator = decorator.andThen(d.decorator(dependencyInjector));
                    }
                    final HttpService decorated = decorator.apply(this);
                    decorated.serviceAdded(cfg);
                    decoratedTHttpServices.put(thriftFunction, decorated);
                }
            }
        }
        this.decoratedTHttpServices = decoratedTHttpServices;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final DecodedRequest decodedRequest = ctx.attr(DECODED_REQUEST);
        if (decodedRequest != null) {
            final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
            invoke(ctx, decodedRequest.serializationFormat, decodedRequest.seqId,
                   decodedRequest.thriftFunction, decodedRequest.decodedReq, responseFuture);
            return HttpResponse.of(responseFuture);
        }

        if (req.method() != HttpMethod.POST) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }

        final SerializationFormat serializationFormat = determineSerializationFormat(req);
        if (serializationFormat == null) {
            return HttpResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   MediaType.PLAIN_TEXT_UTF_8, PROTOCOL_NOT_SUPPORTED);
        }

        if (!validateAcceptHeaders(req, serializationFormat)) {
            return HttpResponse.of(HttpStatus.NOT_ACCEPTABLE,
                                   MediaType.PLAIN_TEXT_UTF_8, ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE);
        }

        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(responseFuture);
        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT);
        req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
           .handle((aReq, cause) -> {
               if (cause != null) {
                   cause = Exceptions.peel(cause);
                   if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
                       return HttpResponse.ofFailure(cause);
                   }
                   final HttpResponse errorRes;
                   if (ctx.config().verboseResponses()) {
                       errorRes = HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                  MediaType.PLAIN_TEXT_UTF_8,
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

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return ExchangeType.UNARY;
    }

    @Nullable
    private SerializationFormat determineSerializationFormat(HttpRequest req) {
        final HttpHeaders headers = req.headers();
        final MediaType contentType = headers.contentType();

        final SerializationFormat serializationFormat;
        if (contentType != null) {
            serializationFormat = findSerializationFormat(contentType);
            if (serializationFormat == null) {
                // Browser clients often send a non-Thrift content type.
                // Choose the default serialization format for some vague media types.
                if (!("text".equals(contentType.type()) &&
                      "plain".equals(contentType.subtype())) &&
                    !("application".equals(contentType.type()) &&
                      "octet-stream".equals(contentType.subtype()))) {
                    return null;
                }
            } else {
                return serializationFormat;
            }
        }

        return defaultSerializationFormat();
    }

    private static boolean validateAcceptHeaders(HttpRequest req, SerializationFormat serializationFormat) {
        // If accept header is present, make sure it is sane. Currently, we do not support accept
        // headers with a different format than the content type header.
        final List<MediaType> acceptTypes = req.headers().accept();
        return acceptTypes.isEmpty() || serializationFormat.mediaTypes().match(acceptTypes) != null;
    }

    @Nullable
    private SerializationFormat findSerializationFormat(MediaType contentType) {
        for (SerializationFormat format : supportedSerializationFormats) {
            if (format.isAccepted(contentType)) {
                return format;
            }
        }

        return null;
    }

    private void decodeAndInvoke(
            ServiceRequestContext ctx, AggregatedHttpRequest req,
            SerializationFormat serializationFormat, CompletableFuture<HttpResponse> httpRes) {

        final int seqId;
        final ThriftFunction f;
        final RpcRequest decodedReq;

        try (HttpData content = req.content()) {
            final ByteBuf buf = content.byteBuf();
            final TByteBufTransport inTransport = new TByteBufTransport(buf);
            final TProtocol inProto = requestProtocolFactories.get(serializationFormat)
                                                              .getProtocol(inTransport);

            final TMessage header;
            final TBase<?, ?> args;

            try {
                // Optionally checks the message length before calling `readMessageBegin()` because
                // Thrift 0.9.x and 0.10.x does not support a correct validation of `readMessageBegin()` for
                // some `TProtocol`s.
                ThriftProtocolUtil.maybeCheckMessageLength(serializationFormat, buf, maxRequestStringLength);

                header = inProto.readMessageBegin();
            } catch (Exception e) {
                logger.debug("{} Failed to decode a {} header:", ctx, serializationFormat, e);

                final HttpStatus httpStatus;
                String message;
                if (e instanceof TProtocolException &&
                    ((TProtocolException) e).getType() == TProtocolException.SIZE_LIMIT) {
                    httpStatus = HttpStatus.REQUEST_ENTITY_TOO_LARGE;
                    message = e.getMessage();
                } else {
                    httpStatus = HttpStatus.BAD_REQUEST;
                    message = "Failed to decode a " + serializationFormat + " header";
                }
                if (ctx.config().verboseResponses()) {
                    message += '\n' + Exceptions.traceText(e);
                }

                httpRes.complete(HttpResponse.of(httpStatus, MediaType.PLAIN_TEXT_UTF_8, message));
                return;
            }

            seqId = header.seqid;

            final byte typeValue = header.type;
            final int colonIdx = header.name.indexOf(':');
            final String serviceName;
            final String methodName;
            if (colonIdx < 0) {
                serviceName = "";
                methodName = header.name;
            } else {
                serviceName = header.name.substring(0, colonIdx);
                methodName = header.name.substring(colonIdx + 1);
            }

            // Basic sanity check. We usually should never fail here.
            if (typeValue != TMessageType.CALL && typeValue != TMessageType.ONEWAY) {
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.INVALID_MESSAGE_TYPE,
                        "unexpected TMessageType: " + typeString(typeValue));

                handlePreDecodeException(ctx, httpRes, cause, serializationFormat, seqId, methodName);
                return;
            }

            // Ensure that such a method exists.
            final ThriftServiceEntry entry = entries().get(serviceName);
            f = entry != null ? entry.metadata.function(methodName) : null;
            if (f == null) {
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.UNKNOWN_METHOD, "unknown method: " + header.name);

                handlePreDecodeException(ctx, httpRes, cause, serializationFormat, seqId, methodName);
                return;
            }

            // Decode the invocation parameters.
            try {
                args = f.newArgs();
                args.read(inProto);
                inProto.readMessageEnd();

                decodedReq = toRpcRequest(f.serviceType(), header.name, args);
                ctx.logBuilder().requestContent(decodedReq, new ThriftCall(header, args));
            } catch (Exception e) {
                final TApplicationException cause;
                if (ctx.config().verboseResponses()) {
                    cause = new TApplicationException(
                            TApplicationException.PROTOCOL_ERROR, "failed to decode arguments: " + e);
                } else {
                    // The exception could have sensitive information such as the required field.
                    // So we don't include the cause message unless verboseResponses returns true.
                    cause = new TApplicationException(TApplicationException.PROTOCOL_ERROR,
                                                      "failed to decode arguments for " + header.name);
                }
                handlePreDecodeException(ctx, httpRes, cause, serializationFormat, seqId, methodName);
                return;
            }
        } finally {
            ctx.logBuilder().requestContent(null, null);
        }

        if (!f.declaredDecorators().isEmpty()) {
            ctx.setAttr(DECODED_REQUEST, new DecodedRequest(serializationFormat, seqId, f, decodedReq));
            try {
                final HttpService decoratedTHttpService = decoratedTHttpServices.get(f);
                assert decoratedTHttpService != null;
                httpRes.complete(decoratedTHttpService.serve(ctx, req.toHttpRequest()));
            } catch (Exception e) {
                handleException(ctx, httpRes, serializationFormat, seqId, f, e);
            }
            return;
        }

        invoke(ctx, serializationFormat, seqId, f, decodedReq, httpRes);
    }

    private static String typeString(byte typeValue) {
        switch (typeValue) {
            case TMessageType.CALL:
                return "CALL";
            case TMessageType.REPLY:
                return "REPLY";
            case TMessageType.EXCEPTION:
                return "EXCEPTION";
            case TMessageType.ONEWAY:
                return "ONEWAY";
            default:
                return "UNKNOWN(" + (typeValue & 0xFF) + ')';
        }
    }

    private void invoke(
            ServiceRequestContext ctx, SerializationFormat serializationFormat, int seqId,
            ThriftFunction func, RpcRequest call, CompletableFuture<HttpResponse> res) {

        final RpcResponse reply;

        try (SafeCloseable ignored = ctx.push()) {
            reply = unwrap().serve(ctx, call);
        } catch (Throwable cause) {
            handleException(ctx, res, serializationFormat, seqId, func, cause);
            return;
        }

        reply.handle((result, cause) -> {
            if (func.isOneWay()) {
                handleOneWaySuccess(ctx, reply, res, serializationFormat);
                return null;
            }

            if (cause != null) {
                handleException(ctx, res, serializationFormat, seqId, func, cause);
                return null;
            }

            try {
                handleSuccess(ctx, reply, res, serializationFormat, seqId, func, result);
            } catch (Throwable t) {
                handleException(ctx, res, serializationFormat, seqId, func, t);
                return null;
            }

            return null;
        }).exceptionally(CompletionActions::log);
    }

    private static RpcRequest toRpcRequest(Class<?> serviceType, String method, TBase<?, ?> thriftArgs) {
        requireNonNull(thriftArgs, "thriftArgs");

        // NB: The map returned by FieldMetaData.getStructMetaDataMap() is an EnumMap,
        //     so the parameter ordering is preserved correctly during iteration.
        final Set<? extends TFieldIdEnum> fields =
                ThriftMetadataAccess.getStructMetaDataMap(thriftArgs.getClass()).keySet();

        // Handle the case where the number of arguments is 0 or 1.
        final int numFields = fields.size();
        switch (numFields) {
            case 0:
                return RpcRequest.of(serviceType, method);
            case 1:
                return RpcRequest.of(serviceType, method,
                                     ThriftFieldAccess.get(thriftArgs, fields.iterator().next()));
        }

        // Handle the case where the number of arguments is greater than 1.
        final List<Object> list = new ArrayList<>(numFields);
        for (TFieldIdEnum field : fields) {
            list.add(ThriftFieldAccess.get(thriftArgs, field));
        }

        return RpcRequest.of(serviceType, method, list);
    }

    private void handleSuccess(
            ServiceRequestContext ctx, RpcResponse rpcRes, CompletableFuture<HttpResponse> httpRes,
            SerializationFormat serializationFormat, int seqId, ThriftFunction func, Object returnValue) {

        final TBase<?, ?> wrappedResult = func.newResult();
        func.setSuccess(wrappedResult, returnValue);
        respond(serializationFormat,
                encodeSuccess(ctx, rpcRes, serializationFormat, func.name(), seqId, wrappedResult),
                httpRes);
    }

    private static void handleOneWaySuccess(
            ServiceRequestContext ctx, RpcResponse rpcRes, CompletableFuture<HttpResponse> httpRes,
            SerializationFormat serializationFormat) {
        ctx.logBuilder().responseContent(rpcRes, null);
        respond(serializationFormat, HttpData.empty(), httpRes);
    }

    private void handleException(ServiceRequestContext ctx, CompletableFuture<HttpResponse> res,
                                 SerializationFormat serializationFormat, int seqId,
                                 ThriftFunction func, Throwable cause) {
        final RpcResponse response = handleException(ctx, Exceptions.peel(cause));
        response.handle((result, convertedCause) -> {
            if (convertedCause != null) {
                handleException(ctx, response, res, serializationFormat, seqId, func, convertedCause);
            } else {
                handleSuccess(ctx, response, res, serializationFormat, seqId, func, result);
            }
            return null;
        });
    }

    private RpcResponse handleException(ServiceRequestContext ctx, Throwable cause) {
        final RpcResponse res = exceptionHandler.apply(ctx, cause);
        if (res == null) {
            logger.warn("exceptionHandler.apply() returned null.");
            return RpcResponse.ofFailure(cause);
        }
        return res;
    }

    private void handleException(
            ServiceRequestContext ctx, RpcResponse rpcRes, CompletableFuture<HttpResponse> httpRes,
            SerializationFormat serializationFormat, int seqId, ThriftFunction func, Throwable cause) {

        if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
            httpRes.complete(HttpResponse.ofFailure(cause));
            return;
        }

        final TBase<?, ?> result = func.newResult();
        final HttpData content;
        if (func.setException(result, cause)) {
            content = encodeSuccess(ctx, rpcRes, serializationFormat, func.name(), seqId, result);
        } else {
            content = encodeException(ctx, rpcRes, serializationFormat, seqId, func.name(), cause);
        }

        respond(serializationFormat, content, httpRes);
    }

    private void handlePreDecodeException(
            ServiceRequestContext ctx, CompletableFuture<HttpResponse> httpRes, Throwable cause,
            SerializationFormat serializationFormat, int seqId, String methodName) {

        final HttpData content = encodeException(
                ctx, RpcResponse.ofFailure(cause), serializationFormat, seqId, methodName, cause);
        respond(serializationFormat, content, httpRes);
    }

    private static void respond(SerializationFormat serializationFormat,
                                HttpData content, CompletableFuture<HttpResponse> res) {
        res.complete(HttpResponse.of(HttpStatus.OK, serializationFormat.mediaType(), content));
    }

    private HttpData encodeSuccess(ServiceRequestContext ctx, RpcResponse reply,
                                   SerializationFormat serializationFormat, String methodName, int seqId,
                                   TBase<?, ?> result) {

        final ByteBuf buf = ctx.alloc().buffer(128);
        boolean success = false;
        try {
            final TTransport transport = new TByteBufTransport(buf);
            final TProtocol outProto = responseProtocolFactories.get(serializationFormat)
                                                                .getProtocol(transport);
            final TMessage header = new TMessage(methodName, TMessageType.REPLY, seqId);
            outProto.writeMessageBegin(header);
            result.write(outProto);
            outProto.writeMessageEnd();

            ctx.logBuilder().responseContent(reply, new ThriftReply(header, result));

            final HttpData encoded = HttpData.wrap(buf);
            success = true;
            return encoded;
        } catch (TException e) {
            throw new Error(e); // Should never reach here.
        } finally {
            if (!success) {
                buf.release();
            }
        }
    }

    private HttpData encodeException(ServiceRequestContext ctx,
                                     RpcResponse reply,
                                     SerializationFormat serializationFormat,
                                     int seqId, String methodName, Throwable cause) {

        final TApplicationException appException;
        if (cause instanceof TApplicationException) {
            appException = (TApplicationException) cause;
        } else {
            if (ctx.config().verboseResponses()) {
                appException = new TApplicationException(
                        TApplicationException.INTERNAL_ERROR,
                        "\n---- BEGIN server-side trace ----\n" +
                        Exceptions.traceText(cause) +
                        "---- END server-side trace ----");
            } else {
                appException = new TApplicationException(TApplicationException.INTERNAL_ERROR);
            }

            // Causes are not sent over the wire but just used for RequestLog.
            appException.initCause(cause);
        }

        final ByteBuf buf = ctx.alloc().buffer(128);
        boolean success = false;
        try {
            final TTransport transport = new TByteBufTransport(buf);
            final TProtocol outProto = responseProtocolFactories.get(serializationFormat)
                                                                .getProtocol(transport);
            final TMessage header = new TMessage(methodName, TMessageType.EXCEPTION, seqId);
            outProto.writeMessageBegin(header);
            appException.write(outProto);
            outProto.writeMessageEnd();

            ctx.logBuilder().responseContent(reply, new ThriftReply(header, appException));

            final HttpData encoded = HttpData.wrap(buf);
            success = true;
            return encoded;
        } catch (TException e) {
            throw new Error(e); // Should never reach here.
        } finally {
            if (!success) {
                buf.release();
            }
        }
    }

    private static final class DecodedRequest {

        private final SerializationFormat serializationFormat;
        private final int seqId;
        private final ThriftFunction thriftFunction;
        private final RpcRequest decodedReq;

        private DecodedRequest(SerializationFormat serializationFormat, int seqId,
                               ThriftFunction thriftFunction, RpcRequest decodedReq) {
            this.serializationFormat = serializationFormat;
            this.seqId = seqId;
            this.thriftFunction = thriftFunction;
            this.decodedReq = decodedReq;
        }
    }
}
