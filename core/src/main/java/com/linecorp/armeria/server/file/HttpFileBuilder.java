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

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import com.linecorp.armeria.common.HttpData;

/**
 * Builds an {@link HttpFile} from a file, a classpath resource or an {@link HttpData}.
 * <pre>{@code
 * // Build from a file.
 * HttpFile f = HttpFileBuilder.of(new File("/var/www/index.html"))
 *                             .lastModified(false)
 *                             .setHeader(HttpHeaderNames.CONTENT_LANGUAGE, "en-US")
 *                             .build();
 *
 * // Build from a classpath resource.
 * HttpFile f = HttpFileBuilder.ofResource(MyClass.class.getClassLoader(), "/foo.txt.gz")
 *                             .setHeader(HttpHeaderNames.CONTENT_ENCODING, "gzip")
 *                             .build();
 *
 * // Build from an HttpData. Can be downcast into AggregatedHttpFile.
 * AggregatedHttpFile f = (AggregatedHttpFile)
 *         HttpFileBuilder.of(HttpData.ofUtf8("content"), System.currentTimeMillis())
 *                        .entityTag((pathOrUri, attrs) -> "myCustomEntityTag")
 *                        .build();
 * }</pre>
 */
public abstract class HttpFileBuilder extends AbstractHttpFileBuilder<HttpFileBuilder> {

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the specified {@link File}.
     */
    public static HttpFileBuilder of(File file) {
        return of(requireNonNull(file, "file").toPath());
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the file at the specified
     * {@link Path}.
     */
    public static HttpFileBuilder of(Path path) {
        return new FileSystemHttpFileBuilder(path);
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link AggregatedHttpFile} from the specified
     * {@link HttpData}. The last modified date of the file is set to 'now'. Note that the {@link #build()}
     * method of the returned builder will always return an {@link AggregatedHttpFile}, which guarantees
     * a safe downcast:
     * <pre>{@code
     * AggregatedHttpFile f = (AggregatedHttpFile) HttpFileBuilder.of(HttpData.ofUtf8("foo")).build();
     * }</pre>
     */
    public static HttpFileBuilder of(HttpData data) {
        return of(data, System.currentTimeMillis());
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link AggregatedHttpFile} from the specified
     * {@link HttpData} and {@code lastModifiedMillis}. Note that the {@link #build()} method of the returned
     * builder will always return an {@link AggregatedHttpFile}, which guarantees a safe downcast:
     * <pre>{@code
     * AggregatedHttpFile f = (AggregatedHttpFile) HttpFileBuilder.of(HttpData.ofUtf8("foo"), 1546923055020)
     *                                                             .build();
     * }</pre>
     *
     * @param data the content of the file
     * @param lastModifiedMillis the last modified time represented as the number of milliseconds
     *                           since the epoch
     */
    public static HttpFileBuilder of(HttpData data, long lastModifiedMillis) {
        requireNonNull(data, "data");
        return new HttpDataFileBuilder(data, lastModifiedMillis)
                .autoDetectedContentType(false); // Can't auto-detect because there's no path or URI.
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the classpath resource
     * at the specified {@code path}. This method is a shortcut of
     * {@code HttpFileBuilder.ofResource(HttpFile.class.getClassLoader(), path)}.
     */
    public static HttpFileBuilder ofResource(String path) {
        requireNonNull(path, "path");
        return ofResource(HttpFile.class.getClassLoader(), path);
    }

    /**
     * Returns a new {@link HttpFileBuilder} that builds an {@link HttpFile} from the classpath resource
     * at the specified {@code path} using the specified {@link ClassLoader}.
     */
    public static HttpFileBuilder ofResource(ClassLoader classLoader, String path) {
        requireNonNull(classLoader, "classLoader");
        requireNonNull(path, "path");
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

            return of(f);
        }

        return new ClassPathHttpFileBuilder(url);
    }

    HttpFileBuilder() {}

    /**
     * Returns a newly created {@link HttpFile} with the properties configured so far. If this builder was
     * created with {@link #of(HttpData)} or {@link #of(HttpData, long)}, the returned instance will always be
     * an {@link AggregatedHttpFile}.
     */
    public abstract HttpFile build();

    private static final class FileSystemHttpFileBuilder extends HttpFileBuilder {

        private final Path path;

        FileSystemHttpFileBuilder(Path path) {
            this.path = requireNonNull(path, "path");
        }

        @Override
        public HttpFile build() {
            return new FileSystemHttpFile(path, isContentTypeAutoDetectionEnabled(), clock(), isDateEnabled(),
                                          isLastModifiedEnabled(), entityTagFunction(), headers());
        }
    }

    private static final class ClassPathHttpFileBuilder extends HttpFileBuilder {

        private final URL url;

        ClassPathHttpFileBuilder(URL url) {
            this.url = requireNonNull(url, "url");
        }

        @Override
        public HttpFile build() {
            return new ClassPathHttpFile(url, isContentTypeAutoDetectionEnabled(), clock(), isDateEnabled(),
                                         isLastModifiedEnabled(), entityTagFunction(), headers());
        }
    }

    private static final class NonExistentHttpFileBuilder extends HttpFileBuilder {
        @Override
        public HttpFile build() {
            return HttpFile.nonExistent();
        }
    }

    private static final class HttpDataFileBuilder extends HttpFileBuilder {

        private final HttpData content;
        private final long lastModifiedMillis;

        HttpDataFileBuilder(HttpData content, long lastModifiedMillis) {
            this.content = requireNonNull(content, "content");
            this.lastModifiedMillis = lastModifiedMillis;
        }

        @Override
        public AggregatedHttpFile build() {
            return new HttpDataFile(content, clock(), lastModifiedMillis,
                                    isDateEnabled(), isLastModifiedEnabled(),
                                    entityTagFunction(), headers());
        }
    }
}
