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

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;

final class FileSystemHttpVfs extends AbstractHttpVfs {

    private static final boolean FILE_SEPARATOR_IS_NOT_SLASH = File.separatorChar != '/';

    private final Path rootDir;

    FileSystemHttpVfs(Path rootDir) {
        this.rootDir = requireNonNull(rootDir, "rootDir").toAbsolutePath();
        if (!Files.exists(this.rootDir) || !Files.isDirectory(this.rootDir)) {
            throw new IllegalArgumentException("rootDir: " + rootDir + " (not a directory");
        }
    }

    @Override
    public HttpFile get(String path, Clock clock,
                        @Nullable String contentEncoding) {
        // Replace '/' with the platform dependent file separator if necessary.
        if (FILE_SEPARATOR_IS_NOT_SLASH) {
            path = path.replace(File.separatorChar, '/');
        }

        final HttpFileBuilder builder = HttpFileBuilder.of(Paths.get(rootDir + path));
        return build(builder, clock, path, contentEncoding);
    }

    static HttpFile build(HttpFileBuilder builder,
                          Clock clock,
                          String pathOrUri,
                          @Nullable String contentEncoding) {

        builder.autoDetectedContentType(false);
        builder.clock(clock);

        final MediaType contentType = MimeTypeUtil.guessFromPath(pathOrUri, contentEncoding);
        if (contentType != null) {
            builder.setHeader(HttpHeaderNames.CONTENT_TYPE, contentType);
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
