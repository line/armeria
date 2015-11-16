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

import java.util.List;
import java.util.Optional;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.thrift.ThriftUtil;
import com.linecorp.armeria.server.ServiceCodec.DecodeResult;
import com.linecorp.armeria.server.ServiceCodec.DecodeResultType;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

final class ThriftServiceInvocationContext extends ServiceInvocationContext implements DecodeResult {

    final ThriftFunction func;
    final int seqId;
    final TBase<TBase<?, ?>, TFieldIdEnum> args;
    private String seqIdStr;
    private List<Object> argList;

    ThriftServiceInvocationContext(
            Channel ch, Scheme protocol, String host, String path, String mappedPath,
            String loggerName, Object originalRequest,
            ThriftFunction func, int seqId, TBase<TBase<?, ?>, TFieldIdEnum> args) {
        super(ch, protocol, host, path, mappedPath, loggerName, originalRequest);

        this.func = func;
        this.seqId = seqId;
        this.args = args;
    }

    @Override
    public String method() {
        return func.methodName();
    }

    @Override
    public List<Class<?>> paramTypes() {
        return func.paramTypes();
    }

    @Override
    public Class<?> returnType() {
        return func.returnType();
    }

    @Override
    public String invocationId() {
        String seqIdStr = this.seqIdStr;
        if (seqIdStr == null) {
            this.seqIdStr = seqIdStr = ThriftUtil.seqIdToString(seqId);
        }
        return seqIdStr;
    }

    @Override
    public List<Object> params() {
        List<Object> argList = this.argList;
        if (argList == null) {
            this.argList = argList = ThriftUtil.toJavaParams(args);
        }
        return argList;
    }

    // The methods from DecodeResult:

    @Override
    public DecodeResultType type() {
        return DecodeResultType.SUCCESS;
    }

    @Override
    public ServiceInvocationContext invocationContext() {
        return this;
    }

    @Override
    public ByteBuf errorResponse() {
        throw new IllegalStateException();
    }

    @Override
    public Throwable cause() {
        throw new IllegalStateException();
    }

    @Override
    public Optional<SerializationFormat> decodedSerializationFormat() {
        return Optional.of(scheme().serializationFormat());
    }

    @Override
    public Optional<String> decodedInvocationId() {
        return Optional.of(invocationId());
    }

    @Override
    public Optional<String> decodedMethod() {
        return Optional.of(method());
    }

    @Override
    public Optional<List<Object>> decodedParams() {
        return Optional.of(params());
    }
}
