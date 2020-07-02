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

import java.net.URL;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.CacheControl;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;

/**
 * Builds an {@link HttpFile} from a file, a classpath resource or an {@link HttpData}.
 * <pre>{@code
 * // Build from a file.
 * HttpFile f = HttpFile.builder(Paths.get("/var/www/index.html"))
 *                      .lastModified(false)
 *                      .setHeader(HttpHeaderNames.CONTENT_LANGUAGE, "en-US")
 *                      .build();
 *
 * // Build from a classpath resource.
 * HttpFile f = HttpFile.builder(MyClass.class.getClassLoader(), "/foo.txt.gz")
 *                      .setHeader(HttpHeaderNames.CONTENT_ENCODING, "gzip")
 *                      .build();
 *
 * // Build from an HttpData.
 * HttpFile f = HttpFile.builder(HttpData.ofUtf8("content"), System.currentTimeMillis())
 *                      .entityTag((pathOrUri, attrs) -> "myCustomEntityTag")
 *                      .build();
 * }</pre>
 */
public abstract class HttpFileBuilder extends AbstractHttpFileBuilder {

    HttpFileBuilder() {}

    /**
     * Returns a newly created {@link HttpFile} with the properties configured so far. If this builder was
     * created with {@link HttpFile#builder(HttpData)} or {@link HttpFile#builder(HttpData, long)},
     * the returned instance will always be an {@link AggregatedHttpFile}.
     */
    public abstract HttpFile build();

    // Methods from the supertype that are overridden to change the return type.

    @Override
    public HttpFileBuilder clock(Clock clock) {
        return (HttpFileBuilder) super.clock(clock);
    }

    @Override
    public HttpFileBuilder date(boolean dateEnabled) {
        return (HttpFileBuilder) super.date(dateEnabled);
    }

    @Override
    public HttpFileBuilder lastModified(boolean lastModifiedEnabled) {
        return (HttpFileBuilder) super.lastModified(lastModifiedEnabled);
    }

    @Override
    public HttpFileBuilder autoDetectedContentType(boolean contentTypeAutoDetectionEnabled) {
        return (HttpFileBuilder) super.autoDetectedContentType(contentTypeAutoDetectionEnabled);
    }

    @Override
    public HttpFileBuilder entityTag(boolean enabled) {
        return (HttpFileBuilder) super.entityTag(enabled);
    }

    @Override
    public HttpFileBuilder entityTag(BiFunction<String, HttpFileAttributes, String> entityTagFunction) {
        return (HttpFileBuilder) super.entityTag(entityTagFunction);
    }

    @Override
    public HttpFileBuilder addHeader(CharSequence name, Object value) {
        return (HttpFileBuilder) super.addHeader(name, value);
    }

    @Override
    public HttpFileBuilder addHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (HttpFileBuilder) super.addHeaders(headers);
    }

    @Override
    public HttpFileBuilder setHeader(CharSequence name, Object value) {
        return (HttpFileBuilder) super.setHeader(name, value);
    }

    @Override
    public HttpFileBuilder setHeaders(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (HttpFileBuilder) super.setHeaders(headers);
    }

    @Override
    public HttpFileBuilder contentType(MediaType contentType) {
        return (HttpFileBuilder) super.contentType(contentType);
    }

    @Override
    public HttpFileBuilder contentType(CharSequence contentType) {
        return (HttpFileBuilder) super.contentType(contentType);
    }

    @Override
    public HttpFileBuilder cacheControl(CacheControl cacheControl) {
        return (HttpFileBuilder) super.cacheControl(cacheControl);
    }

    @Override
    public HttpFileBuilder cacheControl(CharSequence cacheControl) {
        return (HttpFileBuilder) super.cacheControl(cacheControl);
    }

    // Builder implementations

    static final class FileSystemHttpFileBuilder extends HttpFileBuilder {

        private final Path path;

        FileSystemHttpFileBuilder(Path path) {
            this.path = requireNonNull(path, "path");
        }

        @Override
        public HttpFile build() {
            return new FileSystemHttpFile(path, isContentTypeAutoDetectionEnabled(), clock(), isDateEnabled(),
                                          isLastModifiedEnabled(), entityTagFunction(), buildHeaders());
        }
    }

    static final class ClassPathHttpFileBuilder extends HttpFileBuilder {

        private final URL url;

        ClassPathHttpFileBuilder(URL url) {
            this.url = requireNonNull(url, "url");
        }

        @Override
        public HttpFile build() {
            return new ClassPathHttpFile(url, isContentTypeAutoDetectionEnabled(), clock(), isDateEnabled(),
                                         isLastModifiedEnabled(), entityTagFunction(), buildHeaders());
        }
    }

    static final class NonExistentHttpFileBuilder extends HttpFileBuilder {
        @Override
        public HttpFile build() {
            return HttpFile.nonExistent();
        }
    }

    static final class HttpDataFileBuilder extends HttpFileBuilder {

        private final HttpData content;
        private final long lastModifiedMillis;

        HttpDataFileBuilder(HttpData content, long lastModifiedMillis) {
            this.content = requireNonNull(content, "content");
            this.lastModifiedMillis = lastModifiedMillis;
        }

        @Override
        public HttpFile build() {
            return new HttpDataFile(content, clock(), lastModifiedMillis,
                                    isDateEnabled(), isLastModifiedEnabled(),
                                    entityTagFunction(), buildHeaders());
        }
    }
}
