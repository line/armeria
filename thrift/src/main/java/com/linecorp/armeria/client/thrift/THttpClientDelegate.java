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

package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

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

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.InvalidResponseException;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.DefaultRpcResponse;
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
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.thrift.TApplicationExceptions;
import com.linecorp.armeria.internal.thrift.ThriftFieldAccess;
import com.linecorp.armeria.internal.thrift.ThriftFunction;
import com.linecorp.armeria.internal.thrift.ThriftServiceMetadata;

final class THttpClientDelegate implements Client<RpcRequest, RpcResponse> {

    private final AtomicInteger nextSeqId = new AtomicInteger();

    private final Client<HttpRequest, HttpResponse> httpClient;
    private final SerializationFormat serializationFormat;
    private final TProtocolFactory protocolFactory;
    private final MediaType mediaType;
    private final Map<Class<?>, ThriftServiceMetadata> metadataMap = new ConcurrentHashMap<>();

    THttpClientDelegate(Client<HttpRequest, HttpResponse> httpClient,
                        SerializationFormat serializationFormat) {
        this.httpClient = httpClient;
        this.serializationFormat = serializationFormat;
        protocolFactory = ThriftProtocolFactories.get(serializationFormat);
        mediaType = serializationFormat.mediaType();
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

            ctx.logBuilder().requestContent(call, new ThriftCall(header, tArgs));

            final HttpRequest httpReq = HttpRequest.of(
                    HttpHeaders.of(HttpMethod.POST, ctx.path())
                               .contentType(mediaType),
                    HttpData.of(outTransport.getArray(), 0, outTransport.length()));

            ctx.logBuilder().deferResponseContent();

            final CompletableFuture<AggregatedHttpMessage> future =
                    httpClient.execute(ctx, httpReq).aggregate();

            future.handle(voidFunction((res, cause) -> {
                if (cause != null) {
                    handlePreDecodeException(ctx, reply, func, Exceptions.peel(cause));
                    return;
                }

                final HttpStatus status = res.headers().status();
                if (status.code() != HttpStatus.OK.code()) {
                    handlePreDecodeException(ctx, reply, func, new InvalidResponseException(status.toString()));
                    return;
                }

                try {
                    handle(ctx, seqId, reply, func, res.content());
                } catch (Throwable t) {
                    handlePreDecodeException(ctx, reply, func, t);
                }
            })).exceptionally(CompletionActions::log);
        } catch (Throwable cause) {
            handlePreDecodeException(ctx, reply, func, cause);
        }

        return reply;
    }

    private static String fullMethod(ClientRequestContext ctx, String method) {
        final String service = ctx.fragment();
        if (Strings.isNullOrEmpty(service)) {
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

    private void handle(ClientRequestContext ctx, int seqId, DefaultRpcResponse reply,
                        ThriftFunction func, HttpData content) throws TException {

        if (func.isOneWay()) {
            handleSuccess(ctx, reply, null, null);
            return;
        }

        if (content.isEmpty()) {
            throw new TApplicationException(TApplicationException.MISSING_RESULT);
        }

        final TMemoryInputTransport inputTransport =
                new TMemoryInputTransport(content.array(), content.offset(), content.length());
        final TProtocol inputProtocol = protocolFactory.getProtocol(inputTransport);

        final TMessage header = inputProtocol.readMessageBegin();
        final TApplicationException appEx = readApplicationException(seqId, func, inputProtocol, header);
        if (appEx != null) {
            handleException(ctx, reply, new ThriftReply(header, appEx), appEx);
            return;
        }

        final TBase<?, ?> result = func.newResult();
        result.read(inputProtocol);
        inputProtocol.readMessageEnd();

        final ThriftReply rawResponseContent = new ThriftReply(header, result);

        for (TFieldIdEnum fieldIdEnum : func.exceptionFields()) {
            if (ThriftFieldAccess.isSet(result, fieldIdEnum)) {
                final TException cause = (TException) ThriftFieldAccess.get(result, fieldIdEnum);
                handleException(ctx, reply, rawResponseContent, cause);
                return;
            }
        }

        final TFieldIdEnum successField = func.successField();
        if (successField == null) { // void method
            handleSuccess(ctx, reply, null, rawResponseContent);
            return;
        }

        if (ThriftFieldAccess.isSet(result, successField)) {
            final Object returnValue = ThriftFieldAccess.get(result, successField);
            handleSuccess(ctx, reply, returnValue, rawResponseContent);
            return;
        }

        handleException(
                ctx, reply, rawResponseContent,
                new TApplicationException(TApplicationException.MISSING_RESULT,
                                          result.getClass().getName() + '.' + successField.getFieldName()));
    }

    @Nullable
    private static TApplicationException readApplicationException(int seqId, ThriftFunction func,
                                                                  TProtocol inputProtocol,
                                                                  TMessage msg) throws TException {
        if (msg.seqid != seqId) {
            throw new TApplicationException(TApplicationException.BAD_SEQUENCE_ID);
        }

        if (!func.name().equals(msg.name)) {
            return new TApplicationException(TApplicationException.WRONG_METHOD_NAME, msg.name);
        }

        if (msg.type == TMessageType.EXCEPTION) {
            final TApplicationException appEx = TApplicationExceptions.read(inputProtocol);
            inputProtocol.readMessageEnd();
            return appEx;
        }

        return null;
    }

    private static void handleSuccess(ClientRequestContext ctx, DefaultRpcResponse reply,
                                      @Nullable Object returnValue, @Nullable ThriftReply rawResponseContent) {
        reply.complete(returnValue);
        ctx.logBuilder().responseContent(reply, rawResponseContent);
    }

    private static void handleException(ClientRequestContext ctx, DefaultRpcResponse reply,
                                        @Nullable ThriftReply rawResponseContent, Exception cause) {
        reply.completeExceptionally(cause);
        ctx.logBuilder().responseContent(reply, rawResponseContent);
    }

    private static void handlePreDecodeException(ClientRequestContext ctx, DefaultRpcResponse reply,
                                                 ThriftFunction thriftMethod, Throwable cause) {
        handleException(ctx, reply, null,
                        decodeException(cause, thriftMethod.declaredExceptions()));
    }

    private static Exception decodeException(Throwable cause,
                                             @Nullable Class<?>[] declaredThrowableExceptions) {
        if (cause instanceof RuntimeException || cause instanceof TException) {
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
