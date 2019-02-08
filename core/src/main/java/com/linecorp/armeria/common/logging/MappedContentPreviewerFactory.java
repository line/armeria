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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;

final class MappedContentPreviewerFactory
        implements ContentPreviewerFactory, ContentPreviewerFactoryCombinable {
    private Map<MediaType, Supplier<ContentPreviewer>> map;

    MappedContentPreviewerFactory() {
        map = new HashMap<>();
    }

    MappedContentPreviewerFactory(Map<MediaType, Supplier<ContentPreviewer>> map) {
        this.map = map;
    }

    @Override
    public ContentPreviewerFactory asImmutable() {
        map = map instanceof ImmutableMap ? map : ImmutableMap.copyOf(map);
        return this;
    }

    @Override
    public ContentPreviewer get(RequestContext ctx, HttpHeaders headers) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            return ContentPreviewer.DISABLED;
        }
        for (Entry<MediaType, Supplier<ContentPreviewer>> entry : map.entrySet()) {
            if (contentType.is(entry.getKey())) {
                return entry.getValue().get();
            }
        }
        return ContentPreviewer.DISABLED;
    }

    @Override
    public ContentPreviewerFactory combine(ContentPreviewerFactory subject) {
        if (subject == ContentPreviewerFactory.DISABLED) {
            return this;
        }
        if (subject instanceof CompositeContentPreviewerFactory) {
            return ((CompositeContentPreviewerFactory) subject).combine(this);
        }
        if (subject instanceof MappedContentPreviewerFactory) {
            map.putAll(((MappedContentPreviewerFactory) subject).map);
            return this;
        }
        return new CompositeContentPreviewerFactory(Arrays.asList(this, subject));
    }
}
