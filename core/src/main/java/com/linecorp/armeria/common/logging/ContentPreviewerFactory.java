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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;

@FunctionalInterface
public interface ContentPreviewerFactory {
    ContentPreviewer get(RequestContext ctx, HttpHeaders headers);

    default ContentPreviewerFactory asImmutable() {
        return this;
    }

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
    static ContentPreviewerFactory of(ContentPreviewerFactory factory1, ContentPreviewerFactory factory2) {
        if (factory1 == DISABLED) {
            return factory2;
        }
        if (factory2 == DISABLED) {
            return factory1;
        }
        if (factory1 instanceof ContentPreviewerFactoryCombinable) {
            return ((ContentPreviewerFactoryCombinable) factory1).combine(factory2);
        }
        if (factory2 instanceof ContentPreviewerFactoryCombinable) {
            return ((ContentPreviewerFactoryCombinable) factory2).combine(factory1);
        }
        return new CompositeContentPreviewerFactory(Arrays.asList(factory1, factory2));
    }

    /**
     * TODO: AddJavadocs.
     */
    static ContentPreviewerFactory of(ContentPreviewerFactory... factories) {
        final List<ContentPreviewerFactory> factoryList = new ArrayList<>();
        ContentPreviewerFactory combined = DISABLED;
        for (ContentPreviewerFactory factory : factories) {
            if (factory == DISABLED) {
                continue;
            }
            if (factory instanceof ContentPreviewerFactoryCombinable) {
                combined = ((ContentPreviewerFactoryCombinable) factory).combine(combined);
            } else if (combined instanceof ContentPreviewerFactoryCombinable) {
                combined = ((ContentPreviewerFactoryCombinable) combined).combine(factory);
            }
            factoryList.add(factory);
        }
        if (combined != DISABLED) {
            factoryList.add(combined);
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

    static ContentPreviewerFactory ofString(int length, Charset defaultCharset, MediaType... contentTypes) {
        return of(() -> ContentPreviewer.ofString(length, defaultCharset), contentTypes);
    }

    static ContentPreviewerFactory ofString(int length, Charset defaultCharset, String... contentTypes) {
        return of(() -> ContentPreviewer.ofString(length, defaultCharset), contentTypes);
    }

    static ContentPreviewerFactory ofString(int length, Charset defaultCharset) {
        return ofString(length, defaultCharset, MediaType.ANY_TEXT_TYPE, MediaType.ANY_APPLICATION_TYPE);
    }

    static ContentPreviewerFactory ofString(int length) {
        return ofString(length, StandardCharsets.ISO_8859_1);
    }
}
