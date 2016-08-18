/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.file;

import static java.util.Objects.requireNonNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import javax.annotation.Nullable;

import com.google.common.net.MediaType;

import com.linecorp.armeria.common.http.HttpData;

/**
 * A virtual file system that provides the files requested by {@link HttpFileService}.
 */
@FunctionalInterface
public interface HttpVfs {

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in an O/S file system.
     */
    static HttpVfs ofFileSystem(String rootDir) {
        return new FileSystemHttpVfs(Paths.get(requireNonNull(rootDir, "rootDir")));
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in an O/S file system.
     */
    static HttpVfs ofFileSystem(Path rootDir) {
        return new FileSystemHttpVfs(rootDir);
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in the current class path.
     */
    static HttpVfs ofClassPath(String rootDir) {
        return ofClassPath(HttpVfs.class.getClassLoader(), rootDir);
    }

    /**
     * Creates a new {@link HttpVfs} with the specified {@code rootDir} in the current class path.
     */
    static HttpVfs ofClassPath(ClassLoader classLoader, String rootDir) {
        return new ClassPathHttpVfs(classLoader, rootDir);
    }

    /**
     * Finds the file at the specified {@code path}.
     *
     *
     * @param path an absolute path whose component separator is {@code '/'}
     * @param contentEncoding the content encoding of the file. Will be non-null for precompressed resources
     *
     * @return the {@link Entry} of the file at the specified {@code path} if found.
     *         {@link Entry#NONE} if not found.
     */
    Entry get(String path, @Nullable String contentEncoding);

    /**
     * A file entry in an {@link HttpVfs}.
     */
    interface Entry {
        /**
         * A non-existent entry.
         */
        Entry NONE = new Entry() {
            @Override
            public MediaType mediaType() {
                throw new IllegalStateException();
            }

            @Nullable
            @Override
            public String contentEncoding() {
                return null;
            }

            @Override
            public long lastModifiedMillis() {
                return 0;
            }

            @Override
            public HttpData readContent() throws IOException {
                throw new FileNotFoundException();
            }

            @Override
            public String toString() {
                return "none";
            }
        };

        /**
         * Returns the MIME type of the entry.
         *
         * @return {@code null} if unknown
         */
        MediaType mediaType();

        /**
         * The content encoding of the entry. Will be set for precompressed files.
         *
         * @return {code null} if not compressed
         */
        @Nullable
        String contentEncoding();

        /**
         * Returns the modification time of the entry.
         *
         * @return {@code 0} if the entry does not exist.
         */
        long lastModifiedMillis();

        /**
         * Reads the content of the entry into a new buffer.
         */
        HttpData readContent() throws IOException;
    }

    /**
     * A skeletal {@link Entry} implementation.
     */
    abstract class AbstractEntry implements Entry {

        private final String path;
        private final MediaType mediaType;
        @Nullable
        private final String contentEncoding;

        /**
         * Creates a new instance with the specified {@code path}.
         */
        protected AbstractEntry(String path, @Nullable String contentEncoding) {
            this(path, MimeTypeUtil.guessFromPath(path, contentEncoding != null), contentEncoding);
        }

        /**
         * Creates a new instance with the specified {@code path} and {@code mediaType}.
         */
        protected AbstractEntry(String path, @Nullable MediaType mediaType, @Nullable String contentEncoding) {
            this.path = requireNonNull(path, "path");
            this.mediaType = mediaType;
            this.contentEncoding = contentEncoding;
        }

        @Override
        public MediaType mediaType() {
            return mediaType;
        }

        @Override
        @Nullable
        public String contentEncoding() {
            return contentEncoding;
        }

        @Override
        public String toString() {
            return path;
        }

        /**
         * Reads the content of the entry into a new buffer.
         * Use {@link #readContent(InputStream, int)} when the length of the stream is known.
         */
        protected HttpData readContent(InputStream in) throws IOException {
            byte[] buf = new byte[Math.max(in.available(), 1024)];
            int endOffset = 0;

            for (;;) {
                final int readBytes = in.read(buf, endOffset, buf.length - endOffset);
                if (readBytes < 0) {
                    break;
                }

                endOffset += readBytes;
                if (endOffset == buf.length) {
                    buf = Arrays.copyOf(buf, buf.length << 1);
                }
            }

            return endOffset != 0 ? HttpData.of(buf, 0, endOffset) : HttpData.EMPTY_DATA;
        }

        /**
         * Reads the content of the entry into a new buffer.
         * Use {@link #readContent(InputStream)} when the length of the stream is unknown.
         */
        protected HttpData readContent(InputStream in, int length) throws IOException {

            if (length == 0) {
                return HttpData.EMPTY_DATA;
            }

            byte[] buf = new byte[length];
            int endOffset = 0;

            for (;;) {
                final int readBytes = in.read(buf, endOffset, buf.length - endOffset);
                if (readBytes < 0) {
                    break;
                }

                endOffset += readBytes;
                if (endOffset == buf.length) {
                    break;
                }
            }

            return HttpData.of(buf, 0, endOffset);
        }
    }

    /**
     * An {@link Entry} whose content is backed by a byte array.
     */
    final class ByteArrayEntry extends AbstractEntry {

        private final long lastModifiedMillis;
        private final HttpData content;

        /**
         * Creates a new instance with the specified {@code path} and byte array.
         */
        public ByteArrayEntry(String path, byte[] content) {
            this(path, content, System.currentTimeMillis());
        }

        /**
         * Creates a new instance with the specified {@code path} and byte array.
         */
        public ByteArrayEntry(String path, byte[] content, long lastModifiedMillis) {
            super(path, null);
            this.content = HttpData.of(requireNonNull(content, "content"));
            this.lastModifiedMillis = lastModifiedMillis;
        }

        /**
         * Creates a new instance with the specified {@code path}, {@code mediaType} and byte array.
         */
        public ByteArrayEntry(String path, MediaType mediaType, byte[] content) {
            this(path, mediaType, content, System.currentTimeMillis());
        }

        /**
         * Creates a new instance with the specified {@code path}, {@code mediaType} and byte array.
         */
        public ByteArrayEntry(String path, MediaType mediaType, byte[] content, long lastModifiedMillis) {
            super(path, mediaType, null);
            this.content = HttpData.of(requireNonNull(content, "content"));
            this.lastModifiedMillis = lastModifiedMillis;
        }

        @Override
        public long lastModifiedMillis() {
            return lastModifiedMillis;
        }

        @Override
        public HttpData readContent() {
            return content;
        }
    }
}
