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
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;

public final class AnnotatedServiceLogUtil {

    private static final Set<Class<?>> wellKnownTypes = ImmutableSet.of(
            HttpRequest.class,
            HttpResponse.class,
            AggregatedHttpObject.class,
            RequestContext.class,
            MultipartFile.class,
            File.class,
            Path.class,
            Multipart.class,
            QueryParams.class,
            Cookies.class,
            HttpHeaders.class
    );

    public static void customize(ObjectMapper objectMapper) {
        final SimpleModule module = new SimpleModule("annotated-service-logging");
        customizeWellKnownSerializers(module);
        module.addSerializer(new AnnotatedRequestJsonSerializer());
        module.addSerializer(new AnnotatedResponseJsonSerializer());
        objectMapper.registerModule(module);
    }

    static void customizeWellKnownSerializers(SimpleModule module) {
        for (Class<?> clazz : wellKnownTypes) {
            module.addSerializer(clazz, new ToStringSerializer(clazz));
        }
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

    private AnnotatedServiceLogUtil() {}
}
