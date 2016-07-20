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

package com.linecorp.armeria.server.http;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.server.ServiceCodec.DecodeResult;
import com.linecorp.armeria.server.ServiceCodec.DecodeResultType;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

final class HttpServiceInvocationContext extends ServiceInvocationContext implements DecodeResult {

    private static final AtomicInteger nextInvocationId = new AtomicInteger();

    private final int invocationId = nextInvocationId.incrementAndGet();
    private String invocationIdStr;

    private Optional<HashMap<String, List<String>>> mappedVariables;

    HttpServiceInvocationContext(Channel ch, Scheme scheme, String host, String path, String mappedPath,
                                 String loggerName, FullHttpRequest originalRequest,
                                 Optional<HashMap<String, List<String>>> variables) {

        super(ch, scheme, host, path, mappedPath, loggerName, originalRequest);
        mappedVariables = variables;
    }

    @Override
    public String invocationId() {
        String invocationIdStr = this.invocationIdStr;
        if (invocationIdStr == null) {
            this.invocationIdStr = invocationIdStr = Long.toString(invocationId & 0xFFFFFFFFL, 16);
        }

        return invocationIdStr;
    }

    @Override
    public String method() {
        return originalRequest().method().name();
    }

    @Override
    public List<Class<?>> paramTypes() {
        return Collections.emptyList();
    }

    @Override
    public Class<?> returnType() {
        return FullHttpResponse.class;
    }

    @Override
    public List<Object> params() {
        // TODO(trustin): Decode the query string in the URI or the content.
        return Collections.emptyList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public FullHttpRequest originalRequest() {
        return super.originalRequest();
    }

    @Override
    public DecodeResultType type() {
        return DecodeResultType.SUCCESS;
    }

    @Override
    public ServiceInvocationContext invocationContext() {
        return this;
    }

    @Override
    public Object errorResponse() {
        throw new IllegalStateException();
    }

    @Override
    public Throwable cause() {
        throw new IllegalStateException();
    }

    @Override
    public SerializationFormat decodedSerializationFormat() {
        return scheme().serializationFormat();
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

    public String getMappedVariables(String key) throws IllegalAccessException {
        if (!mappedVariables.isPresent()) {
            throw new IllegalAccessException("This http service does not support variable mapping: " + path());
        }
        if (mappedVariables.get().containsKey(key)) {
            return mappedVariables.get().get(key).get(0);
        }
        throw new IllegalArgumentException("This routing rule does not contains a such variable name: " + key);
    }

    public List<String> getGlobStrings() throws IllegalAccessException {
        if (!mappedVariables.isPresent()) {
            throw new IllegalAccessException("This http service does not support variable mapping: " + path());
        }
        return mappedVariables.get().get("*");
    }
}
