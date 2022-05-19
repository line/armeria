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

import static java.util.Objects.requireNonNull;

import java.io.File;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An uploaded file received in a {@link Multipart} request.
 */
public interface MultipartFile {

    /**
     * Creates a new {@link MultipartFile}.
     * @param name the name parameter of the {@code "content-disposition"}
     * @param filename the filename parameter of the {@code "content-disposition"}
     *                 header.
     * @param file the file that stores the {@link BodyPart#content()}.
     */
    static MultipartFile of(@Nullable String name, String filename, File file) {
        requireNonNull(file, "file");
        requireNonNull(filename, "filename");
        return new DefaultMultipartFile(name, filename, file);
    }

    /**
     * Returns the {@code name} parameter of the {@code "content-disposition"} header.
     * @see BodyPart#name()
     */
    @Nullable
    String name();

    /**
     * Returns the {@code filename} parameter of the {@code "content-disposition"} header.
     * @see BodyPart#filename()
     */
    String filename();

    /**
     * Returns the file that stores the {@link BodyPart#content()}.
     */
    File file();
}
