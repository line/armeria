/*
 * Copyright 2015 LINE Corporation
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

import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

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
     * @param path an absolute path whose component separator is {@code '/'}
     *
     * @return the {@link Entry} of the file at the specified {@code path} if found.
     *         {@link Entry#NONE} if not found.
     */
    Entry get(String path);

    /**
     * A file entry in an {@link HttpVfs}.
     */
    interface Entry {
        /**
         * A non-existent entry.
         */
        Entry NONE = new Entry() {
            @Override
            public String mimeType() {
                throw new IllegalStateException();
            }

            @Override
            public long lastModifiedMillis() {
                return 0;
            }

            @Override
            public ByteBuf readContent(ByteBufAllocator alloc) throws IOException {
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
        String mimeType();

        /**
         * Returns the modification time of the entry.
         *
         * @return {@code 0} if the entry does not exist.
         */
        long lastModifiedMillis();

        /**
         * Reads the content of the entry into a new buffer.
         */
        ByteBuf readContent(ByteBufAllocator alloc) throws IOException;
    }

    /**
     * A skeletal {@link Entry} implementation.
     */
    abstract class AbstractEntry implements Entry {

        private final String path;
        private final String mimeType;

        /**
         * Creates a new instance with the specified {@code path}.
         */
        protected AbstractEntry(String path) {
            this(path, MimeTypeUtil.guessFromPath(path));
        }

        /**
         * Creates a new instance with the specified {@code path} and {@code mimeType}.
         */
        protected AbstractEntry(String path, @Nullable String mimeType) {
            this.path = requireNonNull(path, "path");
            this.mimeType = mimeType;
        }

        @Override
        public String mimeType() {
            return mimeType;
        }

        @Override
        public String toString() {
            return path;
        }

        /**
         * Reads the content of the entry into a new buffer.
         * Use {@link #readContent(ByteBufAllocator, InputStream, int)} when the length of the stream is known.
         */
        protected ByteBuf readContent(ByteBufAllocator alloc, InputStream in) throws IOException {
            ByteBuf buf = null;
            boolean success = false;
            try {
                buf = alloc.directBuffer();
                for (;;) {
                    if (buf.writeBytes(in, 8192) < 0) {
                        break;
                    }
                }

                success = true;

                if (buf.isReadable()) {
                    return buf;
                } else {
                    buf.release();
                    return Unpooled.EMPTY_BUFFER;
                }
            } finally {
                if (!success && buf != null) {
                    buf.release();
                }
            }
        }

        /**
         * Reads the content of the entry into a new buffer.
         * Use {@link #readContent(ByteBufAllocator, InputStream)} when the length of the stream is unknown.
         */
        protected ByteBuf readContent(ByteBufAllocator alloc, InputStream in, int length) throws IOException {
            if (length == 0) {
                return Unpooled.EMPTY_BUFFER;
            }

            ByteBuf buf = null;
            boolean success = false;
            try {
                buf = alloc.directBuffer(length);

                int remaining = length;
                for (;;) {
                    final int readBytes = buf.writeBytes(in, remaining);
                    if (readBytes < 0) {
                        break;
                    }
                    remaining -= readBytes;
                    if (remaining <= 0) {
                        break;
                    }
                }

                success = true;
                return buf;
            } finally {
                if (!success && buf != null) {
                    buf.release();
                }
            }
        }
    }

    /**
     * An {@link Entry} whose content is backed by a byte array.
     */
    final class ByteArrayEntry extends AbstractEntry {

        private final long lastModifiedMillis = System.currentTimeMillis();
        private final byte[] content;

        /**
         * Creates a new instance with the specified {@code path} and byte array.
         */
        public ByteArrayEntry(String path, byte[] content) {
            super(path);
            this.content = requireNonNull(content, "content");
        }

        /**
         * Creates a new instance with the specified {@code path}, {@code mimeType} and byte array.
         */
        public ByteArrayEntry(String path, String mimeType, byte[] content) {
            super(path, mimeType);
            this.content = requireNonNull(content, "content");
        }

        @Override
        public long lastModifiedMillis() {
            return lastModifiedMillis;
        }

        @Override
        public ByteBuf readContent(ByteBufAllocator alloc) {
            return Unpooled.wrappedBuffer(content);
        }
    }
}
