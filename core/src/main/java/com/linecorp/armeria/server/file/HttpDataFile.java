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

import java.io.IOException;
import java.time.Clock;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.handler.codec.DateFormatter;

final class HttpDataFile extends AbstractHttpFile implements AggregatedHttpFile {

    private final HttpData content;
    private final HttpFileAttributes attrs;

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
        this.content = content;
        this.attrs = attrs;
    }

    @Override
    protected String pathOrUri() {
        return "";
    }

    @Override
    public HttpFileAttributes readAttributes() {
        return attrs;
    }

    @Override
    public HttpHeaders readHeaders() {
        try {
            return super.readHeaders();
        } catch (IOException e) {
            throw new Error(e); // Never reaches here.
        }
    }

    @Override
    protected HttpResponse doRead(HttpHeaders headers, long length,
                                  Executor fileReadExecutor, ByteBufAllocator alloc) {
        if (content instanceof ByteBufHolder) {
            final ByteBufHolder holder = (ByteBufHolder) content;
            return HttpResponse.of(headers, (HttpData) holder.retainedDuplicate());
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
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("content", content())
                          .add("contentType", contentType())
                          .add("lastModified", DateFormatter.format(new Date(attrs.lastModifiedMillis())))
                          .add("dateEnabled", isDateEnabled())
                          .add("lastModifiedEnabled", isLastModifiedEnabled())
                          .add("headers", headers())
                          .toString();
    }
}
