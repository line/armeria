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
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.buffer.ByteBuf;

final class ClassPathHttpFile extends StreamingHttpFile<InputStream> {

    private final URL url;
    @Nullable
    private CompletableFuture<HttpFileAttributes> attrsFuture;

    ClassPathHttpFile(URL url,
                      boolean contentTypeAutoDetectionEnabled,
                      Clock clock,
                      boolean dateEnabled,
                      boolean lastModifiedEnabled,
                      @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                      HttpHeaders headers) {
        super(contentTypeAutoDetectionEnabled ? MediaTypeResolver.ofDefault().guessFromPath(url.toString())
                                              : null,
              clock, dateEnabled, lastModifiedEnabled, entityTagFunction, headers);
        this.url = requireNonNull(url, "url");
    }

    @Override
    protected String pathOrUri() {
        return url.toString();
    }

    @Override
    public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        requireNonNull(fileReadExecutor, "fileReadExecutor");

        if (attrsFuture != null) {
            return attrsFuture;
        }

        return attrsFuture = CompletableFuture.supplyAsync(() -> {
            try {
                final URLConnection conn = url.openConnection();
                final long length = conn.getContentLengthLong();
                final long lastModifiedMillis = conn.getLastModified();
                return new HttpFileAttributes(length, lastModifiedMillis);
            } catch (IOException e) {
                return Exceptions.throwUnsafely(e);
            }
        }, fileReadExecutor);
    }

    @Override
    protected InputStream newStream() throws IOException {
        return url.openStream();
    }

    @Override
    protected int read(InputStream src, ByteBuf dst) throws IOException {
        return dst.writeBytes(src, dst.writableBytes());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("url", url)
                          .add("contentType", contentType())
                          .add("dateEnabled", isDateEnabled())
                          .add("lastModifiedEnabled", isLastModifiedEnabled())
                          .add("additionalHeaders", additionalHeaders())
                          .toString();
    }
}
