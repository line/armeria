/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;

@FunctionalInterface
public interface HttpRequestAs<T> extends HttpMessageAs<T> {

    static HttpRequestAs<Void> ignore() {
        return bytes().map(unused -> null);
    }

    static HttpRequestAs<String> string() {
        return response -> response.aggregate().thenApply(
                agg -> RequestEntity.of(agg.headers(), agg.contentUtf8(), agg.trailers()));
    }

    static HttpRequestAs<byte[]> bytes() {
        return response -> response.aggregate().thenApply(
                agg -> RequestEntity.of(agg.headers(), agg.content().array(), agg.trailers()));
    }

    static <U> HttpRequestAs<U> json(Class<U> clazz) {
        return bytes().map(bytes -> {
            try {
                return JacksonUtil.readValue(bytes, clazz);
            } catch (IOException e) {
                return Exceptions.throwUnsafely(e);
            }
        });
    }

    static <U> HttpRequestAs<U> json(TypeReference<U> typeRef) {
        return bytes().map(bytes -> {
            try {
                return JacksonUtil.readValue(bytes, typeRef);
            } catch (IOException e) {
                return Exceptions.throwUnsafely(e);
            }
        });
    }

    static HttpRequestAs<File> file(File file) {
        return path(file.toPath()).map(path -> file);
    }

    static HttpRequestAs<Path> path(Path path) {
        // TODO(ikhoon): Write content to the file
        return new HttpRequestAs<Path>() {
            @Override
            public CompletableFuture<RequestEntity<Path>> as(HttpRequest request) {
                return null;
            }

            @Override
            public boolean isStreaming() {
                return true;
            }
        };
    }

    CompletableFuture<RequestEntity<T>> as(HttpRequest request);

    @Override
    default <U> HttpRequestAs<U> map(Function<T, @Nullable U> function) {
        return request -> as(request).thenApply(result -> {
            final T content = result.content();
            if (content == null) {
                //noinspection unchecked
                return (RequestEntity<U>) result;
            }

            final U transformed = function.apply(content);
            return RequestEntity.of(result.headers(), transformed, result.trailers());
        });
    }
}
