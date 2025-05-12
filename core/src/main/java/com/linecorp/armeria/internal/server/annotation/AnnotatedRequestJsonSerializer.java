/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.server.annotation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;

final class AnnotatedRequestJsonSerializer extends JsonSerializer<DefaultAnnotatedRequest> {

    @Override
    public Class<DefaultAnnotatedRequest> handledType() {
        return DefaultAnnotatedRequest.class;
    }

    @Override
    public void serialize(DefaultAnnotatedRequest value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeFieldName("params");
        gen.writeStartArray();
        for (int i = 0; i < value.parameters().size(); i++) {
            Object parameter = value.parameters().get(i);
            parameter = maybeUnwrapFuture(parameter);
            if (parameter == null) {
                serializers.defaultSerializeNull(gen);
                continue;
            }
            parameter = handleInternalTypes(parameter);
            if (parameter == null) {
                serializers.defaultSerializeNull(gen);
                continue;
            }
            gen.writeObject(parameter);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }

    @Nullable
    static Object handleInternalTypes(@Nullable Object param) {
        if (param == null) {
            return null;
        }
        if (param instanceof HttpRequest ||
            param instanceof AggregatedHttpObject ||
            param instanceof HttpResponse ||
            param instanceof RequestContext ||
            param instanceof MultipartFile ||
            param instanceof File ||
            param instanceof Path ||
            param instanceof Multipart ||
            param instanceof QueryParams ||
            param instanceof Cookies ||
            param instanceof HttpHeaders) {
            return param.toString();
        }
        return param;
    }

    @Nullable
    static Object maybeUnwrapFuture(@Nullable Object param) {
        if (param == null) {
            return null;
        }
        if (param instanceof CompletableFuture) {
            final CompletableFuture<?> future = (CompletableFuture<?>) param;
            if (!future.isDone() || future.isCompletedExceptionally()) {
                return null;
            }
            return future.join();
        }
        return param;
    }
}
