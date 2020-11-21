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
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

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
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBuf;

/**
 * An {@link HttpService} that handles a Thrift call.
 *
 * @see ThriftProtocolFactories
 */
public final class THttpService extends DecoratingService<RpcRequest, RpcResponse, HttpRequest, HttpResponse>
        implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(THttpService.class);

    private static final String PROTOCOL_NOT_SUPPORTED = "Specified content-type not supported";

    private static final String ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE =
            "Thrift protocol specified in Accept header must match " +
            "the one specified in the content-type header";

    private static final SerializationFormat[] EMPTY_FORMATS = new SerializationFormat[0];

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
        return new THttpService(ThriftCallService.of(implementation),
                                newSupportedSerializationFormats(defaultSerializationFormat,
                                                                 ThriftSerializationFormats.values()));
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

        return new THttpService(ThriftCallService.of(implementation),
                                newSupportedSerializationFormats(defaultSerializationFormat,
                                                                 otherSupportedSerializationFormats));
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

        final SerializationFormat[] supportedSerializationFormatArray = newSupportedSerializationFormats(
                defaultSerializationFormat,
                ThriftSerializationFormats.values());

        return delegate -> new THttpService(delegate, supportedSerializationFormatArray);
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
        return newDecorator(defaultSerializationFormat, Arrays.asList(otherSupportedSerializationFormats));
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

        final SerializationFormat[] supportedSerializationFormatArray = newSupportedSerializationFormats(
                defaultSerializationFormat, otherSupportedSerializationFormats);

        return delegate -> new THttpService(delegate, supportedSerializationFormatArray);
    }

    private static SerializationFormat[] newSupportedSerializationFormats(
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherSupportedSerializationFormats) {

        requireNonNull(defaultSerializationFormat, "defaultSerializationFormat");
        requireNonNull(otherSupportedSerializationFormats, "otherSupportedSerializationFormats");

        final Set<SerializationFormat> set = new LinkedHashSet<>();
        set.add(defaultSerializationFormat);
        Iterables.addAll(set, otherSupportedSerializationFormats);
        return set.toArray(EMPTY_FORMATS);
    }

    private final SerializationFormat[] supportedSerializationFormatArray;
    private final Set<SerializationFormat> supportedSerializationFormats;
    private final ThriftCallService thriftService;

    THttpService(RpcService delegate, SerializationFormat[] supportedSerializationFormatArray) {
        super(delegate);
        thriftService = findThriftService(delegate);

        this.supportedSerializationFormatArray = supportedSerializationFormatArray;
        supportedSerializationFormats = ImmutableSet.copyOf(supportedSerializationFormatArray);
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
        return supportedSerializationFormatArray[0];
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
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
        final HttpResponse res = HttpResponse.from(responseFuture);
        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().defer(RequestLogProperty.REQUEST_CONTENT);
        req.aggregateWithPooledObjects(ctx.eventLoop(), ctx.alloc()).handle((aReq, cause) -> {
            if (cause != null) {
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
        final List<String> acceptHeaders = req.headers().getAll(HttpHeaderNames.ACCEPT);
        return acceptHeaders.isEmpty() ||
               serializationFormat.mediaTypes().matchHeaders(acceptHeaders) != null;
    }

    @Nullable
    private SerializationFormat findSerializationFormat(MediaType contentType) {
        for (SerializationFormat format : supportedSerializationFormatArray) {
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
            final TByteBufTransport inTransport = new TByteBufTransport(content.byteBuf());
            final TProtocol inProto = ThriftSerializationFormats.get(serializationFormat)
                                                                .getProtocol(inTransport);

            final TMessage header;
            final TBase<?, ?> args;

            try {
                header = inProto.readMessageBegin();
            } catch (Exception e) {
                logger.debug("{} Failed to decode a {} header:", ctx, serializationFormat, e);

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
                // Failed to decode the invocation parameters.
                logger.debug("{} Failed to decode Thrift arguments:", ctx, e);

                final TApplicationException cause = new TApplicationException(
                        TApplicationException.PROTOCOL_ERROR, "failed to decode arguments: " + e);

                handlePreDecodeException(ctx, httpRes, cause, serializationFormat, seqId, methodName);
                return;
            }
        } finally {
            ctx.logBuilder().requestContent(null, null);
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
            handleException(ctx, RpcResponse.ofFailure(cause), res, serializationFormat, seqId, func, cause);
            return;
        }

        reply.handle((result, cause) -> {
            if (func.isOneWay()) {
                handleOneWaySuccess(ctx, reply, res, serializationFormat);
                return null;
            }

            if (cause != null) {
                handleException(ctx, reply, res, serializationFormat, seqId, func, cause);
                return null;
            }

            try {
                handleSuccess(ctx, reply, res, serializationFormat, seqId, func, result);
            } catch (Throwable t) {
                handleException(ctx, RpcResponse.ofFailure(t), res, serializationFormat, seqId, func, t);
            }

            return null;
        }).exceptionally(CompletionActions::log);
    }

    private static RpcRequest toRpcRequest(Class<?> serviceType, String method, TBase<?, ?> thriftArgs) {
        requireNonNull(thriftArgs, "thriftArgs");

        // NB: The map returned by FieldMetaData.getStructMetaDataMap() is an EnumMap,
        //     so the parameter ordering is preserved correctly during iteration.
        final Set<? extends TFieldIdEnum> fields =
                FieldMetaData.getStructMetaDataMap(thriftArgs.getClass()).keySet();

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

    private static void handleSuccess(
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

    private static void handleException(
            ServiceRequestContext ctx, RpcResponse rpcRes, CompletableFuture<HttpResponse> httpRes,
            SerializationFormat serializationFormat, int seqId, ThriftFunction func, Throwable cause) {

        if (cause instanceof HttpStatusException) {
            httpRes.complete(HttpResponse.of(((HttpStatusException) cause).httpStatus()));
            return;
        }

        if (cause instanceof HttpResponseException) {
            httpRes.complete(((HttpResponseException) cause).httpResponse());
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

    private static void handlePreDecodeException(
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

    private static HttpData encodeSuccess(ServiceRequestContext ctx,
                                          RpcResponse reply,
                                          SerializationFormat serializationFormat,
                                          String methodName, int seqId,
                                          TBase<?, ?> result) {

        final ByteBuf buf = ctx.alloc().buffer(128);
        boolean success = false;
        try {
            final TTransport transport = new TByteBufTransport(buf);
            final TProtocol outProto = ThriftSerializationFormats.get(serializationFormat)
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

    private static HttpData encodeException(ServiceRequestContext ctx,
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
            final TProtocol outProto = ThriftSerializationFormats.get(serializationFormat)
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
}
