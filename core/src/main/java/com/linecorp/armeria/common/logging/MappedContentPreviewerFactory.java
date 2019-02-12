/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;

final class MappedContentPreviewerFactory implements ContentPreviewerFactory {
    final Set<Entry<MediaType, Supplier<ContentPreviewer>>> entries;

    MappedContentPreviewerFactory(Map<MediaType, Supplier<ContentPreviewer>> map) {
        this(requireNonNull(map, "map").entrySet());
    }

    MappedContentPreviewerFactory(Set<Entry<MediaType, Supplier<ContentPreviewer>>> entries) {
        this.entries = ImmutableSet.copyOf(requireNonNull(entries, "entries"));
    }

    @Override
    public ContentPreviewer get(RequestContext ctx, HttpHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return ContentPreviewer.DISABLED;
        }
        for (Entry<MediaType, Supplier<ContentPreviewer>> entry : entries) {
            if (contentType.is(entry.getKey())) {
                return entry.getValue().get();
            }
        }
        return ContentPreviewer.DISABLED;
    }
}
