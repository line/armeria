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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A complete HTTP file whose attributes and content are readily available.
 */
public interface AggregatedHttpFile {

    /**
     * Creates a new {@link AggregatedHttpFile} which streams the specified {@link HttpData}. This method is
     * a shortcut for {@code AggregatedHttpFile.of(data, System.currentTimeMillis()}.
     */
    static AggregatedHttpFile of(HttpData data) {
        return builder(data).build();
    }

    /**
     * Creates a new {@link AggregatedHttpFile} with the specified {@link HttpData} with the specified
     * {@code lastModifiedMillis}.
     *
     * @param data the data that provides the content of an HTTP response
     * @param lastModifiedMillis when the {@code data} has been last modified, represented as the number of
     *                           millisecond since the epoch
     */
    static AggregatedHttpFile of(HttpData data, long lastModifiedMillis) {
        requireNonNull(data, "data");
        return builder(data, lastModifiedMillis).build();
    }

    /**
     * Returns an {@link AggregatedHttpFile} which represents a non-existent file.
     */
    static AggregatedHttpFile nonExistent() {
        return NonExistentAggregatedHttpFile.INSTANCE;
    }

    /**
     * Returns a new {@link AggregatedHttpFileBuilder} that builds an {@link AggregatedHttpFile} from
     * the specified {@link HttpData}. The last modified date of the file is set to 'now'.
     */
    static AggregatedHttpFileBuilder builder(HttpData data) {
        return builder(data, System.currentTimeMillis());
    }

    /**
     * Returns a new {@link AggregatedHttpFileBuilder} that builds an {@link AggregatedHttpFile} from
     * the specified {@link HttpData} and {@code lastModifiedMillis}.
     *
     * @param data the content of the file
     * @param lastModifiedMillis the last modified time represented as the number of milliseconds
     *                           since the epoch
     */
    static AggregatedHttpFileBuilder builder(HttpData data, long lastModifiedMillis) {
        requireNonNull(data, "data");
        return new AggregatedHttpFileBuilder(data, lastModifiedMillis)
                .autoDetectedContentType(false); // Can't auto-detect because there's no path or URI.
    }

    /**
     * Returns the attributes of the file.
     *
     * @return the attributes, or {@code null} if the file does not exist.
     */
    @Nullable
    HttpFileAttributes attributes();

    /**
     * Returns the attributes of this file as {@link ResponseHeaders}, which could be useful for building
     * a response for a {@code HEAD} request.
     *
     * @return the headers, or {@code null} if the file does not exist.
     */
    @Nullable
    ResponseHeaders headers();

    /**
     * Returns the {@link AggregatedHttpResponse} generated from this file.
     *
     * @return the {@link AggregatedHttpResponse} of the file, or {@code null} if the file does not exist.
     */
    @Nullable
    default AggregatedHttpResponse response() {
        final ResponseHeaders headers = headers();
        if (headers == null) {
            return null;
        } else {
            final HttpData content = content();
            assert content != null;
            return AggregatedHttpResponse.of(headers, content);
        }
    }

    /**
     * Returns the content of the file.
     *
     * @return the content, or {@code null} if the file does not exist.
     */
    @Nullable
    HttpData content();

    /**
     * Converts this file into an {@link HttpFile}.
     *
     * @return the {@link HttpFile} converted from this file.
     */
    HttpFile toHttpFile();
}
