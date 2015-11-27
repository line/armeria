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

package com.linecorp.armeria.server.thrift;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TBaseAsyncProcessor;
import org.apache.thrift.TBaseProcessor;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftUtil;
import com.linecorp.armeria.server.ServiceCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.EmptyArrays;

final class ThriftServiceCodec implements ServiceCodec {

    @SuppressWarnings("ThrowableInstanceNeverThrown")
    private static final Exception HTTP_METHOD_NOT_ALLOWED_EXCEPTION =
            new IllegalArgumentException("HTTP method not allowed");
    @SuppressWarnings("ThrowableInstanceNeverThrown")
    private static final Exception MIME_TYPE_MUST_BE_THRIFT =
            new IllegalArgumentException("MIME type must be application/x-thrift");
    @SuppressWarnings("ThrowableInstanceNeverThrown")
    private static final Exception THRIFT_PROTOCOL_NOT_SUPPORTED =
            new IllegalArgumentException("Specified Thrift protocol not supported");
    @SuppressWarnings("ThrowableInstanceNeverThrown")
    private static final Exception ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE =
            new IllegalArgumentException("Thrift protocol specified in Accept header must match the one " +
                                         "specified in Content-Type header");

    static {
        HTTP_METHOD_NOT_ALLOWED_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    private static final Logger logger = LoggerFactory.getLogger(ThriftServiceCodec.class);

    private final SerializationFormat defaultSerializationFormat;

    private final Set<SerializationFormat> allowedSerializationFormats;
    private final Object service;
    private final String serviceLoggerName;

    /**
     * A map whose key is a method name and whose value is {@link AsyncProcessFunction} or {@link ProcessFunction}.
     */
    private final Map<String, ThriftFunction> functions = new HashMap<>();

    private static final Map<SerializationFormat, ThreadLocalTProtocol> FORMAT_TO_THREAD_LOCAL_IN_PROTOCOL =
            createFormatToThreadLocalTProtocolMap();
    private static final Map<SerializationFormat, ThreadLocalTProtocol> FORMAT_TO_THREAD_LOCAL_OUT_PROTOCOL =
            createFormatToThreadLocalTProtocolMap();

    ThriftServiceCodec(Object service, SerializationFormat defaultSerializationFormat,
                       Set<SerializationFormat> allowedSerializationFormats) {
        requireNonNull(allowedSerializationFormats, "allowedSerializationFormats");
        this.service = requireNonNull(service, "service");
        this.defaultSerializationFormat =
                requireNonNull(defaultSerializationFormat, "defaultSerializationFormat");
        this.allowedSerializationFormats = Collections.unmodifiableSet(allowedSerializationFormats);

        // Build the map of method names and their corresponding process functions.
        final Set<String> methodNames = new HashSet<>();
        final Class<?> serviceClass = service.getClass();
        final ClassLoader serviceClassLoader = serviceClass.getClassLoader();

        for (Class<?> iface : serviceClass.getInterfaces()) {
            final Map<String, AsyncProcessFunction<?, ?, ?>> asyncProcessMap;
            asyncProcessMap = getThriftAsyncProcessMap(service, iface, serviceClassLoader);
            if (asyncProcessMap != null) {
                asyncProcessMap.forEach(
                        (name, func) -> registerFunction(methodNames, serviceClass, name, func));
            }

            final Map<String, ProcessFunction<?, ?>> processMap;
            processMap = getThriftProcessMap(service, iface, serviceClassLoader);
            if (processMap != null) {
                processMap.forEach(
                        (name, func) -> registerFunction(methodNames, serviceClass, name, func));
            }
        }

        if (functions.isEmpty()) {
            throw new IllegalArgumentException('\'' + serviceClass.getName() +
                                               "' is not a Thrift service implementation.");
        }

        serviceLoggerName = service.getClass().getName();
    }

    @SuppressWarnings("rawtypes")
    private void registerFunction(Set<String> methodNames, Class<?> serviceClass, String name, Object func) {
        checkDuplicateMethodName(methodNames, serviceClass, name);
        methodNames.add(name);
        try {
            final ThriftFunction f;
            if (func instanceof ProcessFunction) {
                f = new ThriftFunction((ProcessFunction) func);
            } else {
                f = new ThriftFunction((AsyncProcessFunction) func);
            }
            functions.put(name, f);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to retrieve function metadata: " +
                                               serviceClass.getName() + '.' + name + "()", e);
        }
    }

    private static void checkDuplicateMethodName(Set<String> methodNames, Class<?> serviceClass, String name) {
        if (methodNames.contains(name)) {
            throw new IllegalArgumentException(
                    '\'' + serviceClass.getName() +
                    "\' implements multiple Thrift service interfaces with a duplicate method name: " + name);
        }
    }

    private static Map<String, ProcessFunction<?, ?>> getThriftProcessMap(
            Object service, Class<?> iface, ClassLoader loader) {

        final String name = iface.getName();
        if (!name.endsWith("$Iface")) {
            return null;
        }

        final String processorName = name.substring(0, name.length() - 5) + "Processor";
        try {
            final Class<?> processorClass = Class.forName(processorName, false, loader);
            if (!TBaseProcessor.class.isAssignableFrom(processorClass)) {
                return null;
            }

            final Constructor<?> processorConstructor = processorClass.getConstructor(iface);

            @SuppressWarnings("rawtypes")
            final TBaseProcessor processor = (TBaseProcessor) processorConstructor.newInstance(service);

            @SuppressWarnings("unchecked")
            Map<String, ProcessFunction<?, ?>> processMap =
                    (Map<String, ProcessFunction<?, ?>>) processor.getProcessMapView();

            return processMap;
        } catch (Exception e) {
            logger.debug("Failed to retrieve the process map from: {}", iface, e);
            return null;
        }
    }

    private static Map<String, AsyncProcessFunction<?, ?, ?>> getThriftAsyncProcessMap(
            Object service, Class<?> iface, ClassLoader loader) {

        final String name = iface.getName();
        if (!name.endsWith("$AsyncIface")) {
            return null;
        }

        final String processorName = name.substring(0, name.length() - 10) + "AsyncProcessor";
        try {
            Class<?> processorClass = Class.forName(processorName, false, loader);
            if (!TBaseAsyncProcessor.class.isAssignableFrom(processorClass)) {
                return null;
            }

            final Constructor<?> processorConstructor = processorClass.getConstructor(iface);

            @SuppressWarnings("rawtypes")
            final TBaseAsyncProcessor processor = (TBaseAsyncProcessor) processorConstructor.newInstance(service);

            @SuppressWarnings("unchecked")
            Map<String, AsyncProcessFunction<?, ?, ?>> processMap =
                    (Map<String, AsyncProcessFunction<?, ?, ?>>) processor.getProcessMapView();

            return processMap;
        } catch (Exception e) {
            logger.debug("Failed to retrieve the asynchronous process map from:: {}", iface, e);
            return null;
        }
    }

    Object thriftService() {
        return service;
    }

    Set<SerializationFormat> allowedSerializationFormats() {
        return allowedSerializationFormats;
    }

    SerializationFormat defaultSerializationFormat() {
        return defaultSerializationFormat;
    }

    @Override
    public DecodeResult decodeRequest(
            Channel ch, SessionProtocol sessionProtocol, String hostname, String path, String mappedPath,
            ByteBuf in, Object originalRequest, Promise<Object> promise) throws Exception {

        final SerializationFormat serializationFormat;
        try {
            serializationFormat = validateRequestAndDetermineSerializationFormat(originalRequest);
        } catch (InvalidHttpRequestException e) {
            return new DefaultDecodeResult(
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                                                e.httpResponseStatus),
                    e.getCause());
        }

        final TProtocol inProto = FORMAT_TO_THREAD_LOCAL_IN_PROTOCOL.get(serializationFormat).get();
        inProto.reset();
        final TByteBufTransport inTransport = (TByteBufTransport) inProto.getTransport();
        inTransport.reset(in);

        try {
            final TMessage header = inProto.readMessageBegin();
            final byte typeValue = header.type;
            final int seqId = header.seqid;
            final String methodName = header.name;

            // Basic sanity check. We usually should never fail here.
            if (typeValue != TMessageType.CALL && typeValue != TMessageType.ONEWAY) {
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.INVALID_MESSAGE_TYPE,
                        "unexpected TMessageType: " + typeString(typeValue));

                return new ThriftDecodeFailureResult(
                        serializationFormat,
                        encodeException(ch.alloc(), serializationFormat, methodName, seqId, cause),
                        cause, seqId, methodName, null);
            }


            // Ensure that such a method exists.
            final ThriftFunction f = functions.get(methodName);
            if (f == null) {
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.UNKNOWN_METHOD, "unknown method: " + methodName);

                return new ThriftDecodeFailureResult(
                        serializationFormat,
                        encodeException(ch.alloc(), serializationFormat, methodName, seqId, cause),
                        cause, seqId, methodName, null);
            }

            // Decode the invocation parameters.
            final TBase<TBase<?, ?>, TFieldIdEnum> args;
            try {
                if (f.isAsync()) {
                    AsyncProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>, Object> asyncFunc = f.asyncFunc();

                    args = asyncFunc.getEmptyArgsInstance();
                    args.read(inProto);
                    inProto.readMessageEnd();
                } else {
                    ProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>> syncFunc = f.syncFunc();

                    args = syncFunc.getEmptyArgsInstance();
                    args.read(inProto);
                    inProto.readMessageEnd();
                }
            } catch (Exception e) {
                // Failed to decode the invocation parameters.
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.PROTOCOL_ERROR, "argument decode failure: " + e);

                return new ThriftDecodeFailureResult(
                        serializationFormat,
                        encodeException(ch.alloc(), serializationFormat, methodName, seqId, cause),
                        cause, seqId, methodName, null);
            }

            return new ThriftServiceInvocationContext(
                    ch, Scheme.of(serializationFormat, sessionProtocol),
                    hostname, path, mappedPath, serviceLoggerName, originalRequest, f, seqId, args);
        } finally {
            inTransport.clear();
        }
    }

    @Override
    public boolean failureResponseFailsSession(ServiceInvocationContext ctx) {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ByteBuf encodeResponse(ServiceInvocationContext ctx, Object response) throws Exception {
        final ThriftServiceInvocationContext tctx = (ThriftServiceInvocationContext) ctx;
        final ThriftFunction func = tctx.func;
        if (func.isOneway()) {
            return null;
        }

        TBase<TBase<?, ?>, TFieldIdEnum> result;
        if (func.isResult(response)) {
            result = (TBase<TBase<?, ?>, TFieldIdEnum>) response;
        } else {
            result = func.newResult();
            func.setSuccess(result, response);
        }

        return encodeSuccess(tctx, result);
    }

    @Override
    public ByteBuf encodeFailureResponse(ServiceInvocationContext ctx, Throwable cause) throws Exception {
        final ThriftServiceInvocationContext tctx = (ThriftServiceInvocationContext) ctx;
        final ThriftFunction func = tctx.func;
        if (func.isOneway()) {
            return encodeException(tctx, cause);
        }

        try {
            TBase<TBase<?, ?>, TFieldIdEnum> result = func.newResult();
            if (func.setException(result, cause)) {
                return encodeSuccess(tctx, result);
            } else {
                return encodeException(tctx, cause);
            }
        } catch (Throwable t) {
            return encodeException(tctx, t);
        }
    }

    private static ByteBuf encodeSuccess(ThriftServiceInvocationContext ctx,
                                         TBase<TBase<?, ?>, TFieldIdEnum> result) {

        final TProtocol outProto = FORMAT_TO_THREAD_LOCAL_OUT_PROTOCOL.get(ctx.scheme().serializationFormat())
                .get();
        outProto.reset();
        final TByteBufTransport outTransport = (TByteBufTransport) outProto.getTransport();
        final ByteBuf out = ctx.alloc().buffer();
        outTransport.reset(out);
        try {
            outProto.writeMessageBegin(new TMessage(ctx.method(), TMessageType.REPLY, ctx.seqId));
            result.write(outProto);
            outProto.writeMessageEnd();
        } catch (TException e) {
            throw new Error(e); // Should never reach here.
        } finally {
            outTransport.clear();
        }

        return out;
    }

    private static ByteBuf encodeException(ThriftServiceInvocationContext ctx, Throwable t) {

        if (t instanceof TApplicationException) {
            return encodeException(ctx.alloc(), ctx.scheme().serializationFormat(),
                                   ctx.method(), ctx.seqId, (TApplicationException) t);
        } else {
            return encodeException(ctx.alloc(), ctx.scheme().serializationFormat(),
                                   ctx.method(), ctx.seqId,
                                   new TApplicationException(
                                           TApplicationException.INTERNAL_ERROR, t.toString()));
        }
    }

    private static ByteBuf encodeException(
            ByteBufAllocator alloc, SerializationFormat serializationFormat,
            String methodName, int seqId,
            TApplicationException cause) {

        final TProtocol outProto = FORMAT_TO_THREAD_LOCAL_OUT_PROTOCOL.get(serializationFormat).get();
        outProto.reset();
        final TByteBufTransport outTransport = (TByteBufTransport) outProto.getTransport();
        final ByteBuf out = alloc.buffer();

        outTransport.reset(out);
        try {
            outProto.writeMessageBegin(new TMessage(methodName, TMessageType.EXCEPTION, seqId));
            cause.write(outProto);
            outProto.writeMessageEnd();
        } catch (TException e) {
            throw new Error(e); // Should never reach here.
        } finally {
            outTransport.clear();
        }

        return out;
    }

    private SerializationFormat validateRequestAndDetermineSerializationFormat(Object originalRequest)
            throws InvalidHttpRequestException {
        if (!(originalRequest instanceof HttpRequest)) {
            return defaultSerializationFormat;
        }
        final SerializationFormat serializationFormat;
        HttpRequest httpRequest = (HttpRequest) originalRequest;
        if (httpRequest.method() != HttpMethod.POST) {
            throw new InvalidHttpRequestException(HttpResponseStatus.METHOD_NOT_ALLOWED,
                                                  HTTP_METHOD_NOT_ALLOWED_EXCEPTION);
        }

        final String contentTypeHeader = httpRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeHeader != null) {
            validateContentType(contentTypeHeader);
            serializationFormat = SerializationFormat.fromMimeType(contentTypeHeader)
                    .orElse(defaultSerializationFormat);
            if (!allowedSerializationFormats.contains(serializationFormat)) {
                throw new InvalidHttpRequestException(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                                                      THRIFT_PROTOCOL_NOT_SUPPORTED);
            }
        } else {
            serializationFormat = defaultSerializationFormat;
        }

        final String acceptHeader = httpRequest.headers().get(HttpHeaderNames.ACCEPT);
        if (acceptHeader != null) {
            validateAccept(acceptHeader);
            // If accept header is present, make sure it is sane. Currently, we do not support accept
            // headers with a different format than the content type header.
            SerializationFormat outputSerializationFormat =
                    SerializationFormat.fromMimeType(acceptHeader).orElse(serializationFormat);
            if (outputSerializationFormat != serializationFormat) {
                throw new InvalidHttpRequestException(HttpResponseStatus.NOT_ACCEPTABLE,
                                                      ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE);
            }
        }
        return serializationFormat;
    }

    private static void validateContentType(String contentType) throws InvalidHttpRequestException {
        if (!contentType.contains("application/x-thrift")) {
            throw new InvalidHttpRequestException(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                                                  MIME_TYPE_MUST_BE_THRIFT);
        }
    }

    private static void validateAccept(String accept) throws InvalidHttpRequestException {
        if (accept.contains("application/x-thrift") ||
            accept.contains("*/*") ||
            accept.contains("application/*")) {
            return;
        }

        throw new InvalidHttpRequestException(HttpResponseStatus.NOT_ACCEPTABLE,
                                              MIME_TYPE_MUST_BE_THRIFT);
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

    private static final class InvalidHttpRequestException extends Exception {
        private static final long serialVersionUID = -8742741687997488293L;

        private final HttpResponseStatus httpResponseStatus;

        private InvalidHttpRequestException(HttpResponseStatus httpResponseStatus, Exception cause) {
            super(cause);
            this.httpResponseStatus = httpResponseStatus;
        }
    }

    private static final class ThriftDecodeFailureResult extends DefaultDecodeResult {

        private final SerializationFormat serializationFormat;
        private final int seqId;
        private final String method;
        private final TBase<TBase<?, ?>, TFieldIdEnum> params;
        private String seqIdStr;
        private List<Object> paramList;

        ThriftDecodeFailureResult(SerializationFormat serializationFormat, Object response, Throwable cause,
                                  int seqId, String method, TBase<TBase<?, ?>, TFieldIdEnum> params) {

            super(response, cause);

            this.serializationFormat = serializationFormat;
            this.seqId = seqId;
            this.method = method;
            this.params = params;
        }

        @Override
        public Optional<SerializationFormat> decodedSerializationFormat() {
            return Optional.of(serializationFormat);
        }

        @Override
        public Optional<String> decodedInvocationId() {
            String seqIdStr = this.seqIdStr;
            if (seqIdStr == null) {
                this.seqIdStr = seqIdStr = ThriftUtil.seqIdToString(seqId);
            }
            return Optional.of(seqIdStr);
        }

        @Override
        public Optional<String> decodedMethod() {
            return Optional.of(method);
        }

        @Override
        public Optional<List<Object>> decodedParams() {
            if (params == null) {
                return Optional.empty();
            }

            List<Object> paramList = this.paramList;
            if (paramList == null) {
                this.paramList = paramList = ThriftUtil.toJavaParams(params);
            }

            return Optional.of(paramList);
        }
    }

    private static Map<SerializationFormat, ThreadLocalTProtocol> createFormatToThreadLocalTProtocolMap() {
        return Collections.unmodifiableMap(
                SerializationFormat.ofThrift().stream().collect(
                        Collectors.toMap(Function.identity(),
                                         f -> new ThreadLocalTProtocol(ThriftProtocolFactories.get(f)))));
    }

    private static final class ThreadLocalTProtocol extends ThreadLocal<TProtocol> {

        private final TProtocolFactory protoFactory;

        private ThreadLocalTProtocol(TProtocolFactory protoFactory) {
            this.protoFactory = protoFactory;
        }

        @Override
        protected TProtocol initialValue() {
            return protoFactory.getProtocol(new TByteBufTransport());
        }
    }
}
