/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.file;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.file.HttpFileBuilder.ClassPathHttpFileBuilder;
import com.linecorp.armeria.server.file.HttpFileBuilder.FileSystemHttpFileBuilder;
import com.linecorp.armeria.server.file.HttpFileBuilder.HttpDataFileBuilder;
import com.linecorp.armeria.server.file.HttpFileBuilder.NonExistentHttpFileBuilder;

import io.netty.buffer.ByteBufAllocator;

/**
 * A file-like HTTP resource which yields an {@link HttpResponse}.
 * <pre>{@code
 * HttpFile faviconFile = HttpFile.of(new File("/var/www/favicon.ico"));
 * ServerBuilder builder = Server.builder();
 * builder.service("/favicon.ico", faviconFile.asService());
 * Server server = builder.build();
 * }</pre>
 *
 * @see HttpFileBuilder
 */
public interface HttpFile {

    /**
     * Creates a new {@link HttpFile} which streams the specified {@link File}.
     */
    static HttpFile of(File file) {
        return builder(file).build();
    }

    /**
     * Creates a new {@link HttpFile} which streams the file at the specified {@link Path}.
     */
    static HttpFile of(Path path) {
        return builder(path).build();
    }

    /**
     * Creates a new {@link HttpFile} which streams the resource at the specified {@code path}, loaded by
     * the specified {@link ClassLoader}.
     *
     * @param classLoader the {@link ClassLoader} which will load the resource at the {@code path}
     * @param path the path to the resource
     */
    static HttpFile of(ClassLoader classLoader, String path) {
        return builder(classLoader, path).build();
    }

    /**
     * Creates a new {@link AggregatedHttpFile} which streams the specified {@link HttpData}. This method is
     * a shortcut for {@code HttpFile.of(data, System.currentTimeMillis()}.
     */
    static AggregatedHttpFile of(HttpData data) {
        return (AggregatedHttpFile) builder(data).build();
    }

    /**
     * Creates a new {@link AggregatedHttpFile} which streams the specified {@link HttpData} with the specified
     * {@code lastModifiedMillis}.
     *
     * @param data the data that provides the content of an HTTP response
     * @param lastModifiedMillis when the {@code data} has been last modified, represented as the number of
     *                           millisecond since the epoch
     */
    static AggregatedHttpFile of(HttpData data, long lastModifiedMillis) {
        return (AggregatedHttpFile) builder(data, lastModifiedMillis).build();
    }

    /**
     * Creates a new {@link HttpFile} which streams the resource at the specified {@code path}. This method is
     * a shortcut for {@code HttpFile.of(HttpFile.class.getClassLoader(), path)}.
     *
     * @deprecated Use {@link #of(ClassLoader, String)}.
     */
    @Deprecated
    static HttpFile ofResource(String path) {
        return of(HttpFile.class.getClassLoader(), path);
    }

    /**
     * Creates a new {@link HttpFile} which streams the resource at the specified {@code path}, loaded by
     * the specified {@link ClassLoader}.
     *
     * @param classLoader the {@link ClassLoader} which will load the resource at the {@code path}
     * @param path the path to the resource
     *
     * @deprecated Use {@link #of(ClassLoader, String)}.
     */
    @Deprecated
    static HttpFile ofResource(ClassLoader classLoader, String path) {
        return of(classLoader, path);
    }

    /**
     * Creates a new {@link HttpFile} which caches the content and attributes of the specified {@link HttpFile}.
     * The cache is automatically invalidated when the {@link HttpFile} is updated.
     *
     * @param file the {@link HttpFile} to cache
     * @param maxCachingLength the maximum allowed length of the {@link HttpFile} to cache. if the length of
     *                         the {@link HttpFile} exceeds this value, no caching will be performed.
     */
    static HttpFile ofCached(HttpFile file, int maxCachingLength) {
        requireNonNull(file, "file");
        checkArgument(maxCachingLength >= 0, "maxCachingLength: %s (expected: >= 0)", maxCachingLength);
        if (maxCachingLength == 0) {
            return file;
        } else {
            return new CachingHttpFile(file, maxCachingLength);
        }
    }

    /**
     * Returns an {@link AggregatedHttpFile} which represents a non-existent file.
     */
    static AggregatedHttpFile nonExistent() {
        return NonExistentHttpFile.INSTANCE;
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the file at the specified
     * {@link File}.
     */
    static HttpFileBuilder builder(File file) {
        return builder(requireNonNull(file, "file").toPath());
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the file at the specified
     * {@link Path}.
     */
    static HttpFileBuilder builder(Path path) {
        return new FileSystemHttpFileBuilder(path);
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link AggregatedHttpFile} from the specified
     * {@link HttpData}. The last modified date of the file is set to 'now'. Note that the
     * {@link HttpFileBuilder#build()} method of the returned builder will always return
     * an {@link AggregatedHttpFile}, which guarantees a safe downcast:
     * <pre>{@code
     * AggregatedHttpFile f = (AggregatedHttpFile) HttpFile.builder(HttpData.ofUtf8("foo")).build();
     * }</pre>
     */
    static HttpFileBuilder builder(HttpData data) {
        return builder(data, System.currentTimeMillis());
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link AggregatedHttpFile} from the specified
     * {@link HttpData} and {@code lastModifiedMillis}. Note that the {@link HttpFileBuilder#build()} method
     * of the returned builder will always return an {@link AggregatedHttpFile}, which guarantees a safe
     * downcast:
     * <pre>{@code
     * AggregatedHttpFile f = (AggregatedHttpFile) HttpFile.builder(HttpData.ofUtf8("foo"), 1546923055020)
     *                                                     .build();
     * }</pre>
     *
     * @param data the content of the file
     * @param lastModifiedMillis the last modified time represented as the number of milliseconds
     *                           since the epoch
     */
    static HttpFileBuilder builder(HttpData data, long lastModifiedMillis) {
        requireNonNull(data, "data");
        return new HttpDataFileBuilder(data, lastModifiedMillis)
                .autoDetectedContentType(false); // Can't auto-detect because there's no path or URI.
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the classpath resource
     * at the specified {@code path} using the specified {@link ClassLoader}.
     */
    static HttpFileBuilder builder(ClassLoader classLoader, String path) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(path, "path");

        // Strip the leading slash.
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Retrieve the resource URL.
        final URL url = classLoader.getResource(path);
        if (url == null || url.getPath().endsWith("/")) {
            // Non-existent resource.
            return new NonExistentHttpFileBuilder();
        }

        // Convert to a real file if possible.
        if ("file".equals(url.getProtocol())) {
            File f;
            try {
                f = new File(url.toURI());
            } catch (URISyntaxException ignored) {
                f = new File(url.getPath());
            }

            return builder(f.toPath());
        }

        return new ClassPathHttpFileBuilder(url);
    }

    /**
     * Retrieves the attributes of this file.
     *
     * @return the attributes of this file, or {@code null} if the file does not exist.
     * @throws IOException if failed to retrieve the attributes of this file.
     */
    @Nullable
    HttpFileAttributes readAttributes() throws IOException;

    /**
     * Reads the attributes of this file as {@link ResponseHeaders}, which could be useful for building
     * a response for a {@code HEAD} request.
     *
     * @return the headers, or {@code null} if the file does not exist.
     * @throws IOException if failed to retrieve the attributes of this file.
     */
    @Nullable
    ResponseHeaders readHeaders() throws IOException;

    /**
     * Starts to stream this file into the returned {@link HttpResponse}.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param alloc the {@link ByteBufAllocator} which will allocate the buffers that hold the content of
     *              the file
     * @return the response, or {@code null} if the file does not exist.
     */
    @Nullable
    HttpResponse read(Executor fileReadExecutor, ByteBufAllocator alloc);

    /**
     * Converts this file into an {@link AggregatedHttpFile}.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     *
     * @return a {@link CompletableFuture} which will complete when the aggregation process is finished, or
     *         a {@link CompletableFuture} successfully completed with {@code this}, if this file is already
     *         an {@link AggregatedHttpFile}.
     */
    CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor);

    /**
     * Converts this file into an {@link AggregatedHttpFile}. {@link AggregatedHttpFile#content()} will
     * return a pooled object, and the caller must ensure to release it. If you don't know what this means,
     * use {@link #aggregate(Executor)}.
     *
     * @param fileReadExecutor the {@link Executor} which will perform the read operations against the file
     * @param alloc the {@link ByteBufAllocator} which will allocate the content buffer
     *
     * @return a {@link CompletableFuture} which will complete when the aggregation process is finished, or
     *         a {@link CompletableFuture} successfully completed with {@code this}, if this file is already
     *         an {@link AggregatedHttpFile}.
     */
    CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                     ByteBufAllocator alloc);

    /**
     * Returns an {@link HttpService} which serves the file for {@code HEAD} and {@code GET} requests.
     */
    HttpService asService();
}
