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

import java.util.List;
import java.util.Optional;

import org.apache.thrift.TBase;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;

import com.linecorp.armeria.client.ClientCodec.EncodeResult;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.thrift.ThriftUtil;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * Object that contains Thrift Method Invocation Information
 */
class ThriftInvocation extends ServiceInvocationContext implements EncodeResult {

    private final TMessage tMessage;
    @SuppressWarnings("rawtypes")
    private final TBase tArgs;
    private final ThriftMethod method;
    private final AsyncMethodCallback<?> asyncMethodCallback;
    private final ByteBuf content;

    ThriftInvocation(
            Channel ch, Scheme scheme, String host, String path, String mappedPath,
            String loggerName, ByteBuf content,
            TMessage tMessage, ThriftMethod method, @SuppressWarnings("rawtypes") TBase tArgs,
            AsyncMethodCallback<?> asyncMethodCallback) {

        super(ch, scheme, host, path, mappedPath, loggerName, content);

        this.content = requireNonNull(content);
        this.tMessage = requireNonNull(tMessage, "tMessage");
        this.tArgs = requireNonNull(tArgs, "tArgs");
        this.method = requireNonNull(method, "method");
        this.asyncMethodCallback = asyncMethodCallback;
    }

    TMessage tMessage() {
        return tMessage;
    }

    boolean isOneway() {
        return TMessageType.ONEWAY == tMessage.type;
    }

    int seqId() {
        return tMessage().seqid;
    }

    ThriftMethod thriftMethod() {
        return method;
    }

    @Override
    public String invocationId() {
        return ThriftUtil.seqIdToString(tMessage().seqid);
    }

    @Override
    public String method() {
        return tMessage().name;
    }

    @Override
    public List<Class<?>> paramTypes() {
        return method.paramTypes();
    }

    @Override
    public Class<?> returnType() {
        return method.returnType();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> params() {
        return ThriftUtil.toJavaParams(tArgs);
    }

    public AsyncMethodCallback<?> asyncMethodCallback() {
        return asyncMethodCallback;
    }

    @Override
    public boolean isSuccess() {
        return true;
    }

    @Override
    public ServiceInvocationContext invocationContext() {
        return this;
    }

    @Override
    public ByteBuf content() {
        return content;
    }

    @Override
    public Throwable cause() {
        throw new IllegalStateException("A successful result does not have a cause.");
    }

    @Override
    public Optional<String> encodedHost() {
        return Optional.of(host());
    }

    @Override
    public Optional<String> encodedPath() {
        return Optional.of(path());
    }

    @Override
    public Optional<Scheme> encodedScheme() {
        return Optional.of(scheme());
    }
}
