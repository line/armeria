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

import java.time.Clock;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.ByteBufAccessMode;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.DateFormatter;

final class HttpDataFile extends AbstractHttpFile implements AggregatedHttpFile {

    private final HttpData content;
    private final HttpFileAttributes attrs;
    @Nullable
    private UnmodifiableFuture<AggregatedHttpFile> aggregateFuture;

    HttpDataFile(HttpData content,
                 Clock clock,
                 long lastModifiedMillis,
                 boolean dateEnabled,
                 boolean lastModifiedEnabled,
                 @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                 HttpHeaders headers) {
        this(content, null, clock, new HttpFileAttributes(content.length(), lastModifiedMillis),
             dateEnabled, lastModifiedEnabled, entityTagFunction, headers);
    }

    private HttpDataFile(HttpData content,
                         @Nullable MediaType contentType,
                         Clock clock,
                         HttpFileAttributes attrs,
                         boolean dateEnabled,
                         boolean lastModifiedEnabled,
                         @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                         HttpHeaders headers) {
        super(contentType, clock, dateEnabled, lastModifiedEnabled, entityTagFunction, headers);
        this.content = requireNonNull(content, "content");
        this.attrs = requireNonNull(attrs, "attrs");
    }

    @Override
    protected String pathOrUri() {
        return "";
    }

    @Nonnull
    @Override
    public HttpFileAttributes attributes() {
        return attrs;
    }

    @Override
    public CompletableFuture<HttpFileAttributes> readAttributes(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(attrs);
    }

    @Nonnull
    @Override
    public ResponseHeaders headers() {
        return readHeaders(attrs);
    }

    @Override
    public CompletableFuture<ResponseHeaders> readHeaders(Executor fileReadExecutor) {
        return UnmodifiableFuture.completedFuture(headers());
    }

    @Override
    protected HttpResponse doRead(ResponseHeaders headers, long length,
                                  Executor fileReadExecutor, ByteBufAllocator alloc) {
        if (content.isPooled()) {
            final ByteBuf buf = content.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE);
            return HttpResponse.of(headers, HttpData.wrap(buf));
        } else {
            return HttpResponse.of(headers, content);
        }
    }

    @Nonnull
    @Override
    public HttpData content() {
        return content;
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregate(Executor fileReadExecutor) {
        return aggregate();
    }

    private UnmodifiableFuture<AggregatedHttpFile> aggregate() {
        if (aggregateFuture == null) {
            aggregateFuture = UnmodifiableFuture.completedFuture(this);
        }
        return aggregateFuture;
    }

    @Override
    public CompletableFuture<AggregatedHttpFile> aggregateWithPooledObjects(Executor fileReadExecutor,
                                                                            ByteBufAllocator alloc) {
        return aggregate();
    }

    @Override
    public HttpFile toHttpFile() {
        return this;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("content", content())
                          .add("contentType", contentType())
                          .add("lastModified", DateFormatter.format(new Date(attrs.lastModifiedMillis())))
                          .add("dateEnabled", isDateEnabled())
                          .add("lastModifiedEnabled", isLastModifiedEnabled())
                          .add("additionalHeaders", additionalHeaders())
                          .toString();
    }
}
