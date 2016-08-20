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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.http.HttpData;

final class FileSystemHttpVfs implements HttpVfs {

    private static final boolean FILE_SEPARATOR_IS_NOT_SLASH = File.separatorChar != '/';

    private final Path rootDir;

    FileSystemHttpVfs(Path rootDir) {
        this.rootDir = requireNonNull(rootDir, "rootDir").toAbsolutePath();
        if (!Files.exists(this.rootDir) || !Files.isDirectory(this.rootDir)) {
            throw new IllegalArgumentException("rootDir: " + rootDir + " (not a directory");
        }
    }

    @Override
    public Entry get(String path, @Nullable String contentEncoding) {
        // Replace '/' with the platform dependent file separator if necessary.
        if (FILE_SEPARATOR_IS_NOT_SLASH) {
            path = path.replace(File.separatorChar, '/');
        }

        final File f = new File(rootDir + path);
        if (!f.isFile() || !f.canRead()) {
            return Entry.NONE;
        }

        return new FileSystemEntry(f, path, contentEncoding);
    }

    @Override
    public String toString() {
        return "file:" + rootDir;
    }

    static final class FileSystemEntry extends AbstractEntry {

        private final File file;

        FileSystemEntry(File file, String path, @Nullable String contentEncoding) {
            super(path, contentEncoding);
            this.file = file;
        }

        @Override
        public long lastModifiedMillis() {
            return file.lastModified();
        }

        @Override
        public HttpData readContent() throws IOException {
            final long fileLength = file.length();
            if (fileLength > Integer.MAX_VALUE) {
                throw new IOException("file too large: " + file + " (" + fileLength + " bytes)");
            }

            try (InputStream in = new FileInputStream(file)) {
                return readContent(in, (int) fileLength);
            }
        }
    }
}
