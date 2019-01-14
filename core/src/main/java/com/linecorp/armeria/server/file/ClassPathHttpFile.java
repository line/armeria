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
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.Clock;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;

final class ClassPathHttpFile extends StreamingHttpFile<InputStream> {

    private final URL url;
    @Nullable
    private HttpFileAttributes attrs;

    ClassPathHttpFile(URL url,
                      boolean contentTypeAutoDetectionEnabled,
                      Clock clock,
                      boolean dateEnabled,
                      boolean lastModifiedEnabled,
                      @Nullable BiFunction<String, HttpFileAttributes, String> entityTagFunction,
                      HttpHeaders headers) {
        super(contentTypeAutoDetectionEnabled ? MimeTypeUtil.guessFromPath(url.toString()) : null,
              clock, dateEnabled, lastModifiedEnabled, entityTagFunction, headers);
        this.url = url;
    }

    @Override
    protected String pathOrUri() {
        return url.toString();
    }

    @Override
    public HttpFileAttributes readAttributes() throws IOException {
        if (attrs == null) {
            final URLConnection conn = url.openConnection();
            final long length = conn.getContentLengthLong();
            final long lastModifiedMillis = conn.getLastModified();
            attrs = new HttpFileAttributes(length, lastModifiedMillis);
        }
        return attrs;
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
                          .add("headers", headers())
                          .toString();
    }
}
