/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.MediaType;

import io.netty.util.AsciiString;

/**
 * A body part headers.
 */
public interface BodyPartHeaders extends Iterable<Entry<AsciiString, String>> {

    // Forked from https://github.com/oracle/helidon/blob/ab23ce10cb55043e5e4beea1037a65bb8968354b/media/multipart/src/main/java/io/helidon/media/multipart/BodyPartHeaders.java#

    /**
     * Returns an empty {@link BodyPartHeaders}.
     */
    static BodyPartHeaders of() {
        return DefaultBodyPartHeaders.EMPTY;
    }

    /**
     * Returns a new {@link BodyPartHeadersBuilder}.
     */
    static BodyPartHeadersBuilder builder() {
        return new BodyPartHeadersBuilder();
    }

    /**
     * Returns the {@code Content-Disposition} header.
     */
    @Nullable
    ContentDisposition contentDisposition();

    /**
     * Returns the parsed {@code "content-type"} header.
     *
     * @return the parsed {@link MediaType} if present and valid, or {@code null} otherwise.
     */
    @Nullable
    MediaType contentType();

    /**
     * Returns the value of a header with the specified {@code name}. If there are more than one value for
     * the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the first header value if the header is found, or {@code null} if there's no such header
     */
    @Nullable
    String get(CharSequence name);

    /**
     * Returns all values for the header with the specified name. The returned {@link List} can't be modified.
     *
     * @param name the name of the header to retrieve
     * @return a {@link List} of header values or an empty {@link List} if there is no such header.
     */
    List<String> getAll(CharSequence name);
}
