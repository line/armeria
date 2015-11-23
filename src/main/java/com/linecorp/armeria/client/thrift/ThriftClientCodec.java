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

package com.linecorp.armeria.client.thrift;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransportException;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.Promise;

/**
 * Thrift {@link ClientCodec}.
 */
public class ThriftClientCodec implements ClientCodec {

    private static final Map<Class<?>, Map<String, ThriftMethod>> methodMapCache = new HashMap<>();

    private static final String SYNC_IFACE = "Iface";
    private static final String ASYNC_IFACE = "AsyncIface";

    private final URI uri;
    private final boolean isAsyncClient;
    private final Map<String, ThriftMethod> methodMap;
    private final TProtocolFactory protocolFactory;
    private final String loggerName;

    private final AtomicInteger seq = new AtomicInteger();

    /**
     * Creates a new instance.
     */
    public ThriftClientCodec(URI uri, Class<?> interfaceClass, TProtocolFactory protocolFactory) {

        requireNonNull(interfaceClass, "interfaceClass");

        this.uri = requireNonNull(uri, "uri");
        this.protocolFactory = requireNonNull(protocolFactory, "protocolFactory");

        final String interfaceName = interfaceClass.getName();
        if (interfaceName.endsWith('$' + ASYNC_IFACE)) {
            isAsyncClient = true;
        } else if (interfaceName.endsWith('$' + SYNC_IFACE)) {
            isAsyncClient = false;
        } else {
            throw new IllegalArgumentException("interfaceClass must be Iface or AsyncIface: " + interfaceName);
        }

        loggerName = interfaceName.substring(0, interfaceName.lastIndexOf('$'));
        methodMap = getThriftMethodMapFromInterface(interfaceClass, isAsyncClient);
    }

    private static Map<String, ThriftMethod> getThriftMethodMapFromInterface(Class<?> interfaceClass,
                                                                             boolean isAsyncInterface) {
        Map<String, ThriftMethod> methodMap = methodMapCache.get(interfaceClass);
        if (methodMap != null) {
            return methodMap;
        }
        methodMap = new HashMap<>();

        String interfaceName = interfaceClass.getName();
        ClassLoader loader = interfaceClass.getClassLoader();

        int interfaceNameSuffixLength = isAsyncInterface ? ASYNC_IFACE.length() : SYNC_IFACE.length();
        final String thriftServiceName =
                interfaceName.substring(0, interfaceName.length() - interfaceNameSuffixLength - 1);

        final Class<?> clientClass;
        try {
            clientClass = Class.forName(thriftServiceName + "$Client", false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Thrift Client Class not found. serviceName:" + thriftServiceName, e);
        }

        for (Method method : interfaceClass.getMethods()) {
            ThriftMethod thriftMethod = new ThriftMethod(clientClass, method, thriftServiceName);
            methodMap.put(method.getName(), thriftMethod);
        }

        Map<String, ThriftMethod> resultMap = Collections.unmodifiableMap(methodMap);
        methodMapCache.put(interfaceClass, resultMap);

        return methodMap;

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public <T> void prepareRequest(Method method, Object[] args, Promise<T> resultPromise) {
        requireNonNull(method, "method");
        requireNonNull(resultPromise, "resultPromise");
        final ThriftMethod thriftMethod = methodMap.get(method.getName());
        if (thriftMethod == null) {
            throw new IllegalStateException("Thrift method not found: " + method.getName());
        }

        if (isAsyncClient) {
            AsyncMethodCallback callback = ThriftMethod.asyncCallback(args);
            if (callback != null) {
                resultPromise.addListener(future -> {
                    if (future.isSuccess()) {
                        callback.onComplete(future.getNow());
                    } else {
                        Exception decodedException = decodeException(future.cause(),
                                                                     thriftMethod.declaredThrowableException());
                        callback.onError(decodedException);
                    }
                });
            }
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public EncodeResult encodeRequest(
            Channel channel, SessionProtocol sessionProtocol, Method method, Object[] args) {

        requireNonNull(channel, "channel");
        requireNonNull(sessionProtocol, "sessionProtocol");
        requireNonNull(method, "method");

        final ThriftMethod thriftMethod = methodMap.get(method.getName());
        if (thriftMethod == null) {
            throw new IllegalStateException("Thrift method not found: " + method.getName());
        }

        final Scheme scheme = Scheme.of(ThriftProtocolFactories.toSerializationFormat(protocolFactory),
                                        sessionProtocol);

        try {
            final ByteBuf outByteBuf = channel.alloc().buffer();
            final TByteBufOutputTransport outTransport = new TByteBufOutputTransport(outByteBuf);
            final TProtocol tProtocol = protocolFactory.getProtocol(outTransport);
            final TMessage tMessage = new TMessage(method.getName(), thriftMethod.methodType(),
                                                   seq.incrementAndGet());

            tProtocol.writeMessageBegin(tMessage);
            final TBase tArgs = thriftMethod.createArgs(isAsyncClient, args);
            tArgs.write(tProtocol);
            tProtocol.writeMessageEnd();

            AsyncMethodCallback asyncMethodCallback = null;
            if (isAsyncClient) {
                asyncMethodCallback = ThriftMethod.asyncCallback(args);
            }
            return new ThriftInvocation(
                    channel, scheme, uri.getHost(), uri.getPath(), uri.getPath(), loggerName, outByteBuf,
                    tMessage, thriftMethod, tArgs, asyncMethodCallback);
        } catch (Exception e) {
            Exception decodedException = decodeException(e, thriftMethod.declaredThrowableException());
            return new ThriftEncodeFailureResult(decodedException, scheme, uri);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T> T decodeResponse(ServiceInvocationContext ctx, ByteBuf content, Object originalResponse)
            throws Exception {
        if (content == null) {
            return null;
        }

        if (!content.isReadable()) {
            ThriftMethod thriftMethod = getThriftMethod(ctx);
            if (thriftMethod != null && thriftMethod.isOneWay()) {
                return null;
            }
            throw new TApplicationException(TApplicationException.MISSING_RESULT, ctx.toString());
        }

        TByteBufInputTransport inputTransport = new TByteBufInputTransport(content);
        TProtocol inputProtocol = protocolFactory.getProtocol(inputTransport);
        TMessage msg = inputProtocol.readMessageBegin();
        if (msg.type == TMessageType.EXCEPTION) {
            TApplicationException ex = TApplicationException.read(inputProtocol);
            inputProtocol.readMessageEnd();
            throw ex;
        }
        ThriftMethod method = methodMap.get(msg.name);
        if (method == null) {
            throw new TApplicationException(TApplicationException.WRONG_METHOD_NAME, msg.name);
        }
        TBase<? extends TBase, TFieldIdEnum> result = method.createResult();
        result.read(inputProtocol);
        inputProtocol.readMessageEnd();

        for (TFieldIdEnum fieldIdEnum : method.getExceptionFields()) {
            if (result.isSet(fieldIdEnum)) {
                throw (TException) result.getFieldValue(fieldIdEnum);
            }
        }

        TFieldIdEnum successField = method.successField();
        if (successField == null) { //void method
            return null;
        }
        if (result.isSet(successField)) {
            return (T) result.getFieldValue(successField);
        }

        throw new TApplicationException(TApplicationException.MISSING_RESULT,
                                        result.getClass().getName() + '.' + successField.getFieldName());
    }

    private ThriftMethod getThriftMethod(ServiceInvocationContext ctx) {
        ThriftMethod method;
        if (ctx instanceof ThriftInvocation) {
            final ThriftInvocation thriftInvocation = (ThriftInvocation) ctx;
            method = thriftInvocation.thriftMethod();
        } else {
            method = methodMap.get(ctx.method());
        }
        return method;
    }

    private static Exception decodeException(Throwable cause, Class<?>[] declaredThrowableExceptions) {
        if (cause instanceof RuntimeException || cause instanceof TTransportException) {
            return (Exception) cause;
        }

        final boolean isDeclaredException;
        if (declaredThrowableExceptions != null) {
            isDeclaredException = Arrays.stream(declaredThrowableExceptions).anyMatch(v -> v.isInstance(cause));
        } else {
            isDeclaredException = false;
        }
        if (isDeclaredException) {
            return (Exception) cause;
        } else if (cause instanceof Error) {
            return new RuntimeException(cause);
        } else {
            return new TTransportException(cause);
        }
    }

    @Override
    public boolean isAsyncClient() {
        return isAsyncClient;
    }

    private static final class ThriftEncodeFailureResult implements EncodeResult {

        private final Throwable cause;
        private final Optional<Scheme> scheme;
        private final Optional<URI> uri;

        ThriftEncodeFailureResult(Throwable cause, Scheme scheme, URI uri) {
            this.cause = requireNonNull(cause, "cause");
            this.scheme = Optional.ofNullable(scheme);
            this.uri = Optional.ofNullable(uri);
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public ServiceInvocationContext invocationContext() {
            throw new IllegalStateException("failed to encode a request; invocation context not available");
        }

        @Override
        public ByteBuf content() {
            throw new IllegalStateException("failed to encode a request; content not available");
        }

        @Override
        public Throwable cause() {
            return cause;
        }

        @Override
        public Optional<String> encodedHost() {
            if (uri.isPresent()) {
                return Optional.ofNullable(uri.get().getHost());
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<String> encodedPath() {
            if (uri.isPresent()) {
                return Optional.ofNullable(uri.get().getPath());
            } else {
                return Optional.empty();
            }
        }

        @Override
        public Optional<Scheme> encodedScheme() {
            return scheme;
        }
    }
}
