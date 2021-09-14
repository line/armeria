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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;

final class FileSystemHttpFile extends StreamingHttpFile<ByteChannel> {

    private final Path path;

    FileSystemHttpFile(Path path,
                       boolean contentTypeAutoDetectionEnabled,
                       Clock clock,
                       boolean dateEnabled,
                       boolean lastModifiedEnabled,
                       @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                       HttpHeaders headers) {
        super(contentTypeAutoDetectionEnabled ? MimeTypeUtil.guessFromPath(path.toString()) : null,
              clock, dateEnabled, lastModifiedEnabled, entityTagFunction, headers);
        this.path = requireNonNull(path, "path");
    }

    @Override
    protected String pathOrUri() {
        return path.toString();
    }

    @Override
    public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.exists(path)) {
                return null;
            }

            try {
                final BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                if (attrs.isRegularFile()) {
                    return new HttpFileAttributes(attrs.size(), attrs.lastModifiedTime().toMillis());
                }
            } catch (NoSuchFileException e) {
                // Non-existent file.
            } catch (IOException e) {
                return Exceptions.throwUnsafely(e);
            }

            return null;
        }, fileReadExecutor);
    }

    @Override
    protected ByteChannel newStream() throws IOException {
        try {
            return Files.newByteChannel(path, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    @Override
    protected int read(ByteChannel src, ByteBuf dst) throws IOException {
        if (src instanceof ScatteringByteChannel) {
            return dst.writeBytes((ScatteringByteChannel) src, dst.writableBytes());
        }

        final int readBytes = src.read(dst.nioBuffer(dst.writerIndex(), dst.writableBytes()));
        if (readBytes > 0) {
            dst.writerIndex(dst.writerIndex() + readBytes);
        }
        return readBytes;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("path", path)
                          .add("contentType", contentType())
                          .add("dateEnabled", isDateEnabled())
                          .add("lastModifiedEnabled", isLastModifiedEnabled())
                          .add("additionalHeaders", additionalHeaders())
                          .toString();
    }
}
