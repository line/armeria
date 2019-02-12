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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

@FunctionalInterface
public interface ContentPreviewerFactory {
    ContentPreviewer get(RequestContext ctx, HttpHeaders headers);

    ContentPreviewerFactory DISABLED = (ctx, headers) -> ContentPreviewer.DISABLED;

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(MediaType contentType, Supplier<ContentPreviewer> supplier) {
        return of((ctx, headers) -> {
            final MediaType type = headers.contentType();
            return type != null && type.is(contentType);
        }, supplier);
    }

    static ContentPreviewerFactory of(String contentType, Supplier<ContentPreviewer> supplier) {
        return of(MediaType.parse(contentType), supplier);
    }

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(BiPredicate<RequestContext, HttpHeaders> predicate,
                                      Supplier<ContentPreviewer> supplier) {
        return (ctx, headers) -> {
            if (predicate.test(ctx, headers)) {
                return supplier.get();
            }
            return ContentPreviewer.DISABLED;
        };
    }

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(ContentPreviewerFactory... factories) {
        return of(Arrays.asList(factories));
    }

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(Iterable<? extends ContentPreviewerFactory> factories) {
        final List<ContentPreviewerFactory> factoryList = new ArrayList<>();
        final Set<Entry<MediaType, Supplier<ContentPreviewer>>> typeSet = new HashSet<>();
        for (ContentPreviewerFactory factory : factories) {
            if (factory == DISABLED) {
                continue;
            }
            if (factory instanceof CompositeContentPreviewerFactory) {
                factoryList.addAll(((CompositeContentPreviewerFactory) factory).factoryList);
            } else if (factory instanceof MappedContentPreviewerFactory) {
                typeSet.addAll(((MappedContentPreviewerFactory) factory).entries);
            } else {
                factoryList.add(factory);
            }
        }
        if (!typeSet.isEmpty()) {
            factoryList.add(new MappedContentPreviewerFactory(typeSet));
        }
        if (factoryList.size() < 2) {
            return factoryList.isEmpty() ? DISABLED : factoryList.get(0);
        }
        return new CompositeContentPreviewerFactory(factoryList);
    }

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(Map<MediaType, Supplier<ContentPreviewer>> map) {
        return new MappedContentPreviewerFactory(map);
    }

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(Supplier<ContentPreviewer> supplier, MediaType... contentTypes) {
        final Map<MediaType, Supplier<ContentPreviewer>> maps = new HashMap<>();
        for (MediaType type : contentTypes) {
            maps.put(type, supplier);
        }
        return of(maps);
    }

    static ContentPreviewerFactory of(Supplier<ContentPreviewer> supplier, String... contentTypes) {
        return of(supplier, Arrays.stream(contentTypes).map(MediaType::parse).toArray(MediaType[]::new));
    }

    static ContentPreviewerFactory ofText(int length, Charset defaultCharset, MediaType... contentTypes) {
        return of(() -> ContentPreviewer.ofText(length, defaultCharset), contentTypes);
    }

    static ContentPreviewerFactory ofText(int length, Charset defaultCharset, String... contentTypes) {
        return of(() -> ContentPreviewer.ofText(length, defaultCharset), contentTypes);
    }

    static ContentPreviewerFactory ofText(int length, Charset defaultCharset) {
        return ofText(length, defaultCharset, MediaType.ANY_TEXT_TYPE, MediaType.ANY_APPLICATION_TYPE);
    }

    static ContentPreviewerFactory ofText(int length) {
        return ofText(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }
}
