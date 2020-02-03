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
package com.linecorp.armeria.common.logging;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;

final class MappedContentPreviewerFactory implements ContentPreviewerFactory {
    final List<Entry<MediaType, Function<? super Charset, ? extends ContentPreviewer>>> entries;

    MappedContentPreviewerFactory(Map<MediaType, Function<? super Charset, ? extends ContentPreviewer>> map) {
        requireNonNull(map, "map");
        final Set<Entry<MediaType, Function<? super Charset, ? extends ContentPreviewer>>> entries =
                map.entrySet();
        entries.forEach(entry -> {
            checkArgument(entry != null, "entry should not be null.");
            checkArgument(entry.getKey() != null, "entry.getKey() should not be null.");
            checkArgument(entry.getValue() != null, "entry.getValue() should not be null.");
        });
        this.entries = ImmutableList.copyOf(requireNonNull(entries, "entries"));
    }

    @Override
    public ContentPreviewer get(RequestContext ctx, HttpHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return ContentPreviewer.disabled();
        }
        for (Entry<MediaType, Function<? super Charset, ? extends ContentPreviewer>> entry : entries) {
            if (contentType.is(entry.getKey())) {
                return entry.getValue().apply(contentType.charset(ContentPreviewerFactory.defaultCharset()));
            }
        }
        return ContentPreviewer.disabled();
    }
}
