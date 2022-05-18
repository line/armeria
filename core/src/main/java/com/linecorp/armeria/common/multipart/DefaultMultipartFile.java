/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common.multipart;

import java.io.File;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultMultipartFile implements MultipartFile {

    @Nullable
    private final String name;
    private final String filename;
    private final File file;

    DefaultMultipartFile(@Nullable String name, String filename, File file) {
        this.name = name;
        this.filename = filename;
        this.file = file;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String filename() {
        return filename;
    }

    @Override
    public File file() {
        return file;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof MultipartFile)) {
            return false;
        }

        final MultipartFile that = (MultipartFile) o;
        return Objects.equals(name, that.name()) &&
               filename.equals(that.filename()) &&
               file.equals(that.file());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, filename, file);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("filename", filename)
                          .add("file", file)
                          .toString();
    }
}
