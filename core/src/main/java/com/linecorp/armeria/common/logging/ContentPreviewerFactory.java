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

import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;

@FunctionalInterface
public interface ContentPreviewerFactory {

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}
     * through {@code supplier} for the request/response with the {@code contentType}.
     */
    static ContentPreviewerFactory of(MediaType contentType, Supplier<ContentPreviewer> supplier) {
        return of(ImmutableMap.of(contentType, supplier));
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}
     * through {@code supplier} for the request/response with the {@code contentType}.
     */
    static ContentPreviewerFactory of(String contentType, Supplier<ContentPreviewer> supplier) {
        return of(MediaType.parse(contentType), supplier);
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory}
     * which wraps a list of {@link ContentPreviewerFactory}s.
     */
    static ContentPreviewerFactory of(ContentPreviewerFactory... factories) {
        return of(Arrays.asList(requireNonNull(factories, "factories")));
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory}
     * which wraps a list of {@link ContentPreviewerFactory}s.
     */
    static ContentPreviewerFactory of(Iterable<? extends ContentPreviewerFactory> factories) {
        final List<ContentPreviewerFactory> factoryList = new ArrayList<>();
        final Set<Entry<MediaType, Supplier<ContentPreviewer>>> typeSet = new LinkedHashSet<>();
        for (ContentPreviewerFactory factory : factories) {
            if (factory == disabled()) {
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
        switch (factoryList.size()) {
            case 0:
                return disabled();
            case 1:
                return factoryList.get(0);
            default:
                return new CompositeContentPreviewerFactory(factoryList);
        }
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}
     * through the supplier that matches with {@code "Content-Type"} header.
     */
    static ContentPreviewerFactory of(Map<MediaType, Supplier<ContentPreviewer>> map) {
        return new MappedContentPreviewerFactory(map);
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}
     * through {@code supplier} if a request/response mathces any of {@code contentTypes}.
     */
    static ContentPreviewerFactory of(Supplier<ContentPreviewer> supplier, Iterable<MediaType> contentTypes) {
        final Map<MediaType, Supplier<ContentPreviewer>> maps = new HashMap<>();
        for (MediaType type : contentTypes) {
            maps.put(type, supplier);
        }
        return of(maps);
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}
     * through {@code supplier} if a request/response mathces any of {@code contentTypes}.
     */
    static ContentPreviewerFactory of(Supplier<ContentPreviewer> supplier, MediaType... contentTypes) {
        return of(supplier, Arrays.asList(contentTypes));
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} which creates a {@link ContentPreviewer}
     * through {@code supplier} if the content type of a request/response mathces any of {@code contentTypes}.
     */
    static ContentPreviewerFactory of(Supplier<ContentPreviewer> supplier, String... contentTypes) {
        return of(supplier, Arrays.stream(contentTypes).map(MediaType::parse).collect(Collectors.toList()));
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} creating a {@link ContentPreviewer}
     * which produces the text with the maximum {@code length}
     * if the content type of a request/response mathces any of {@code contentTypes}.
     */
    static ContentPreviewerFactory ofText(int length, Charset defaultCharset,
                                          Iterable<MediaType> contentTypes) {
        return of(() -> ContentPreviewer.ofText(length, defaultCharset), contentTypes);
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} creating a {@link ContentPreviewer}
     * which produces the text with the maximum {@code length}
     * if the content type of a request/response mathces any of {@code contentTypes}.
     */
    static ContentPreviewerFactory ofText(int length, Charset defaultCharset, MediaType... contentTypes) {
        return ofText(length, defaultCharset, Arrays.asList(contentTypes));
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} creating a {@link ContentPreviewer}
     * which produces the text with the maximum {@code length} limit
     * if the content type of a request/response matches any of {@code contentTypes}.
     */
    static ContentPreviewerFactory ofText(int length, Charset defaultCharset, String... contentTypes) {
        return ofText(length, defaultCharset, Arrays.stream(contentTypes).map(MediaType::parse).collect(
                Collectors.toList()));
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} creating a {@link ContentPreviewer}
     * which produces the text with the maximum {@code length} limit
     * if the content type of a request/response matches
     * any of {@link ArmeriaHttpUtil#HTTP_TEXTIBLE_MEDIA_TYPES}.
     */
    static ContentPreviewerFactory ofText(int length, Charset defaultCharset) {
        return ofText(length, defaultCharset, ArmeriaHttpUtil.HTTP_TEXTIBLE_MEDIA_TYPES);
    }

    /**
     * Creates a new instance of {@link ContentPreviewerFactory} creating a {@link ContentPreviewer}
     * which produces the text with the maximum {@code length} limit
     * if the content type of a request/response matches
     * any of {@link ArmeriaHttpUtil#HTTP_TEXTIBLE_MEDIA_TYPES}.
     */
    static ContentPreviewerFactory ofText(int length) {
        return ofText(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    static ContentPreviewerFactory disabled() {
        return NoopContentPreviewerFactory.INSTANCE;
    }

    /**
     * Returns a {@link ContentPreviewer}, given {@link RequestContext} and {@link HttpHeaders}.
     */
    ContentPreviewer get(RequestContext ctx, HttpHeaders headers);
}
