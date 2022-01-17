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
package com.linecorp.armeria.server.file;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.RouteUtil;

final class FileSystemHttpVfs extends AbstractBlockingHttpVfs {

    private static final boolean FILE_SEPARATOR_IS_NOT_SLASH = File.separatorChar != '/';

    private final Path rootDir;

    FileSystemHttpVfs(Path rootDir) {
        super(true);
        this.rootDir = requireNonNull(rootDir, "rootDir").toAbsolutePath();
        if (!Files.exists(this.rootDir) || !Files.isDirectory(this.rootDir)) {
            throw new IllegalArgumentException("rootDir: " + rootDir + " (not a directory");
        }
    }

    @Deprecated
    @Override
    protected HttpFile blockingGet(
            Executor fileReadExecutor, String path, Clock clock,
            @Nullable String contentEncoding, HttpHeaders additionalHeaders) {
        return blockingGet(fileReadExecutor, path, clock, contentEncoding, additionalHeaders,
                           MediaTypeResolver.ofDefault());
    }

    @Override
    protected HttpFile blockingGet(
            Executor fileReadExecutor, String path, Clock clock,
            @Nullable String contentEncoding, HttpHeaders additionalHeaders,
            MediaTypeResolver mediaTypeResolver) {

        path = normalizePath(path);

        final HttpFileBuilder builder = HttpFile.builder(Paths.get(rootDir + path));
        return build(builder, clock, path, contentEncoding, additionalHeaders, mediaTypeResolver);
    }

    @Override
    protected boolean blockingCanList(Executor fileReadExecutor, String path) {
        path = normalizePath(path);
        final Path fsPath = Paths.get(rootDir + path);
        return Files.isDirectory(fsPath) && Files.isReadable(fsPath);
    }

    @Override
    protected List<String> blockingList(Executor fileReadExecutor, String path) {
        path = normalizePath(path);
        try (Stream<Path> stream = Files.list(Paths.get(rootDir + path))) {
            return stream.filter(Files::isReadable)
                         .map(p -> {
                             final String fileName = p.getFileName().toString();
                             return Files.isDirectory(p) ? fileName + '/' : fileName;
                         })
                         .sorted(String.CASE_INSENSITIVE_ORDER)
                         .collect(toImmutableList());
        } catch (IOException e) {
            // Failed to retrieve the listing.
            return ImmutableList.of();
        }
    }

    private static String normalizePath(String path) {
        RouteUtil.ensureAbsolutePath(path, "path");
        // Replace '/' with the platform dependent file separator if necessary.
        if (FILE_SEPARATOR_IS_NOT_SLASH) {
            path = path.replace(File.separatorChar, '/');
        }
        return path;
    }

    static HttpFile build(HttpFileBuilder builder,
                          Clock clock,
                          String pathOrUri,
                          @Nullable String contentEncoding,
                          HttpHeaders additionalHeaders, MediaTypeResolver mediaTypeResolver) {

        builder.autoDetectedContentType(false);
        builder.clock(clock);
        builder.setHeaders(additionalHeaders);

        final @Nullable MediaType contentType = mediaTypeResolver.guessFromPath(pathOrUri, contentEncoding);
        if (contentType != null) {
            builder.contentType(contentType);
        }
        if (contentEncoding != null) {
            builder.setHeader(HttpHeaderNames.CONTENT_ENCODING, contentEncoding);
        }

        return builder.build();
    }

    @Override
    public String meterTag() {
        return "file:" + rootDir;
    }
}
