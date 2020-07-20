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
package com.linecorp.armeria.common.multipart;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;

import org.reactivestreams.Publisher;

import com.google.common.collect.Streams;

import com.linecorp.armeria.common.HttpData;

/**
 * A builder class for creating {@link BodyPart} instances.
 */
public final class BodyPartBuilder {

    private static final Multi<HttpData> EMPTY = Multi.empty();

    private BodyPartHeaders headers = BodyPartHeaders.of();
    private Multi<HttpData> content = EMPTY;

    BodyPartBuilder() {}

    /**
     * Sets the specified headers for this part.
     * @param headers headers
     */
    public BodyPartBuilder headers(BodyPartHeaders headers) {
        requireNonNull(headers, "headers");
        this.headers = headers;
        return this;
    }

    // TODO(ikhoon): Add builder methods for content that take `File` and `Path`

    /**
     * Adds a new body part backed by the specified {@link Publisher}.
     * @param publisher publisher for the part content
     */
    public BodyPartBuilder content(Publisher<? extends HttpData> publisher) {
        requireNonNull(publisher, "publisher");
        if (content == EMPTY) {
            content = Multi.from(publisher);
        } else {
            content = Multi.concat(content, publisher);
        }
        return this;
    }

    /**
     * Adds the specified a new body part content.
     */
    public BodyPartBuilder content(String content) {
        requireNonNull(content, "content");
        return content(HttpData.ofUtf8(content));
    }

    /**
     * Adds the specified new body part contents.
     */
    public BodyPartBuilder content(Iterable<? extends CharSequence> contents) {
        requireNonNull(contents, "contents");
        final List<HttpData> wrapped = Streams.stream(contents)
                                              .map(HttpData::ofUtf8)
                                              .collect(toImmutableList());
        return content(Multi.from(wrapped));
    }

    /**
     * Adds the specified {@link HttpData} as a part content.
     */
    public BodyPartBuilder content(HttpData content) {
        requireNonNull(content, "content");
        return content(Multi.singleton(content));
    }

    /**
     * Returns a newly-created {@link BodyPart}.
     */
    public BodyPart build() {
        checkState(content != EMPTY, "Should set at lease one content");
        return new DefaultBodyPart(content, headers);
    }
}
