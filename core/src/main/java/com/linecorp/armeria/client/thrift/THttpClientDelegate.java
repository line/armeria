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

package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.apache.thrift.transport.TTransportException;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.internal.thrift.ThriftFieldAccess;
import com.linecorp.armeria.internal.thrift.ThriftFunction;
import com.linecorp.armeria.internal.thrift.ThriftServiceMetadata;

final class THttpClientDelegate implements Client<RpcRequest, RpcResponse> {

    private final AtomicInteger nextSeqId = new AtomicInteger();

    private final Client<HttpRequest, HttpResponse> httpClient;
    private final String path;
    private final SerializationFormat serializationFormat;
    private final TProtocolFactory protocolFactory;
    private final String mediaType;
    private final Map<Class<?>, ThriftServiceMetadata> metadataMap = new ConcurrentHashMap<>();

    THttpClientDelegate(Client<HttpRequest, HttpResponse> httpClient, String path,
                        SerializationFormat serializationFormat) {

        this.httpClient = httpClient;
        this.path = path;
        this.serializationFormat = serializationFormat;
        protocolFactory = ThriftProtocolFactories.get(serializationFormat);
        mediaType = serializationFormat.mediaType().toString();
    }

    @Override
    public RpcResponse execute(ClientRequestContext ctx, RpcRequest call) throws Exception {
        final int seqId = nextSeqId.incrementAndGet();
        final String method = call.method();
        final List<Object> args = call.params();
        final DefaultRpcResponse reply = new DefaultRpcResponse();

        ctx.logBuilder().serializationFormat(serializationFormat);

        final ThriftFunction func;
        try {
            func = metadata(call.serviceType()).function(method);
            if (func == null) {
                throw new IllegalArgumentException("Thrift method not found: " + method);
            }
        } catch (Throwable cause) {
            reply.completeExceptionally(cause);
            return reply;
        }

        try {
            final TMemoryBuffer outTransport = new TMemoryBuffer(128);
            final TProtocol tProtocol = protocolFactory.getProtocol(outTransport);
            final TMessage header = new TMessage(fullMethod(ctx, method), func.messageType(), seqId);

            tProtocol.writeMessageBegin(header);
            @SuppressWarnings("rawtypes")
            final TBase tArgs = func.newArgs(args);
            tArgs.write(tProtocol);
            tProtocol.writeMessageEnd();

            ctx.logBuilder().requestContent(new ThriftCall(header, tArgs));

            final DefaultHttpRequest httpReq = new DefaultHttpRequest(
                    HttpHeaders.of(HttpMethod.POST, path)
                               .set(HttpHeaderNames.CONTENT_TYPE, mediaType), true);
            httpReq.write(HttpData.of(outTransport.getArray(), 0, outTransport.length()));
            httpReq.close();

            ctx.logBuilder().deferResponseContent();

            final CompletableFuture<AggregatedHttpMessage> future =
                    httpClient.execute(ctx, httpReq).aggregate();

            future.handle(voidFunction((res, cause) -> {
                if (cause != null) {
                    completeExceptionally(ctx, reply, func,
                                          cause instanceof ExecutionException ? cause.getCause() : cause);
                    return;
                }

                final HttpStatus status = res.headers().status();
                if (status.code() != HttpStatus.OK.code()) {
                    completeExceptionally(ctx, reply, func, new InvalidResponseException(status.toString()));
                    return;
                }

                try {
                    reply.complete(decodeResponse(ctx, func, res.content()));
                } catch (Throwable t) {
                    completeExceptionally(ctx, reply, func, t);
                }
            })).exceptionally(CompletionActions::log);
        } catch (Throwable cause) {
            completeExceptionally(ctx, reply, func, cause);
        }

        return reply;
    }

    private static String fullMethod(ClientRequestContext ctx, String method) {
        final String service = ctx.fragment();
        if (service.isEmpty()) {
            return method;
        } else {
            return service + ':' + method;
        }
    }

    private ThriftServiceMetadata metadata(Class<?> serviceType) {
        final ThriftServiceMetadata metadata = metadataMap.get(serviceType);
        if (metadata != null) {
            return metadata;
        }

        return metadataMap.computeIfAbsent(serviceType, ThriftServiceMetadata::new);
    }

    private Object decodeResponse(
            ClientRequestContext ctx, ThriftFunction func, HttpData content) throws TException {

        if (func.isOneWay()) {
            ctx.logBuilder().responseContent(null);
            return null;
        }

        if (content.isEmpty()) {
            throw new TApplicationException(TApplicationException.MISSING_RESULT);
        }

        final TMemoryInputTransport inputTransport =
                new TMemoryInputTransport(content.array(), content.offset(), content.length());
        final TProtocol inputProtocol = protocolFactory.getProtocol(inputTransport);

        final TMessage header = inputProtocol.readMessageBegin();
        final TApplicationException appEx = readApplicationException(func, inputProtocol, header);
        if (appEx != null) {
            ctx.logBuilder().responseContent(new ThriftReply(header, appEx));
            throw appEx;
        }

        TBase<? extends TBase<?, ?>, TFieldIdEnum> result = func.newResult();
        result.read(inputProtocol);
        inputProtocol.readMessageEnd();

        ctx.logBuilder().responseContent(new ThriftReply(header, result));

        for (TFieldIdEnum fieldIdEnum : func.exceptionFields()) {
            if (result.isSet(fieldIdEnum)) {
                throw (TException) ThriftFieldAccess.get(result, fieldIdEnum);
            }
        }

        TFieldIdEnum successField = func.successField();
        if (successField == null) { // void method
            return null;
        }
        if (result.isSet(successField)) {
            return ThriftFieldAccess.get(result, successField);
        }

        throw new TApplicationException(TApplicationException.MISSING_RESULT,
                                        result.getClass().getName() + '.' + successField.getFieldName());
    }

    private static TApplicationException readApplicationException(ThriftFunction func,
                                                                  TProtocol inputProtocol,
                                                                  TMessage msg) throws TException {
        final TApplicationException appEx;
        if (msg.type == TMessageType.EXCEPTION) {
            appEx = TApplicationException.read(inputProtocol);
            inputProtocol.readMessageEnd();
        } else if (!func.name().equals(msg.name)) {
            appEx = new TApplicationException(
                    TApplicationException.WRONG_METHOD_NAME, msg.name);
        } else {
            appEx = null;
        }
        return appEx;
    }

    private static void completeExceptionally(ClientRequestContext ctx, DefaultRpcResponse reply,
                                              ThriftFunction thriftMethod, Throwable cause) {
        ctx.logBuilder().responseContent(null);
        reply.completeExceptionally(decodeException(cause, thriftMethod.declaredExceptions()));
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
}
