/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.linecorp.armeria.internal.common.logging.MaskerAttributeKeys.REQUEST_CONTEXT_KEY;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.RawValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.common.logging.FieldMaskerSelectorProvider;
import com.linecorp.armeria.server.annotation.AnnotatedService;

/**
 * A {@link ContentSanitizer} builder which allows users to specify {@link FieldMaskerSelector}s
 * to decide whether to mask a field.
 */
@UnstableApi
public final class ContentSanitizerBuilder {

    private static final Map<Class<?>, FieldMaskerSelectorProvider<?>> customizersMap;

    static {
        final ImmutableMap.Builder<Class<?>, FieldMaskerSelectorProvider<?>> customizersMapBuilder =
                ImmutableMap.builder();
        @SuppressWarnings("rawtypes")
        final ServiceLoader<FieldMaskerSelectorProvider> loader = ServiceLoader.load(
                FieldMaskerSelectorProvider.class,
                ContentSanitizerBuilder.class.getClassLoader());
        for (FieldMaskerSelectorProvider<?> customizer: loader) {
            customizersMapBuilder.put(customizer.supportedType(), customizer);
        }
        customizersMap = customizersMapBuilder.buildKeepingLast();
    }

    private final ImmutableList.Builder<FieldMaskerSelector<?>> maskerListBuilder = ImmutableList.builder();

    ContentSanitizerBuilder() {}

    /**
     * Adds a {@link FieldMaskerSelector} which decides whether each content field should be masked.
     * The specified {@link FieldMaskerSelector} should be an instance of a pre-defined type corresponding to a
     * service type. e.g. {@link BeanFieldMaskerSelector} should be used to mask content produced by
     * {@link AnnotatedService}.
     * If multiple {@link FieldMaskerSelector}s of the same type are registered, each selector will be
     * sequentially queried for a {@link FieldMasker}. If all {@link FieldMaskerSelector}s return
     * {@link FieldMasker#fallthrough()}, no masking will occur for the field.
     */
    public ContentSanitizerBuilder fieldMaskerSelector(FieldMaskerSelector<?> masker) {
        requireNonNull(masker, "masker");
        Preconditions.checkArgument(!customizersMap.containsKey(masker.getClass()),
                                    "Specified masker should be one of the following types: %s",
                                    customizersMap.keySet());
        maskerListBuilder.add(masker);
        return this;
    }

    /**
     * Builds a {@link ContentSanitizer} which can be used with
     * {@link TextLogFormatterBuilder#contentSanitizer(BiFunction)}.
     */
    public ContentSanitizer<String> buildForText() {
        final ObjectMapper objectMapper = buildObjectMapper();
        return (requestContext, o) -> {
            try {
                return objectMapper.writer()
                                   .withAttribute(REQUEST_CONTEXT_KEY, requestContext)
                                   .writeValueAsString(o);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Builds a {@link ContentSanitizer} which can be used with
     * {@link JsonLogFormatterBuilder#contentSanitizer(BiFunction)}.
     */
    public ContentSanitizer<JsonNode> buildForJson() {
        final ObjectMapper objectMapper = buildObjectMapper();
        return (requestContext, o) -> {
            try {
                final String ser = objectMapper.writer()
                                               .withAttribute(REQUEST_CONTEXT_KEY, requestContext)
                                               .writeValueAsString(o);
                return objectMapper.createObjectNode().rawValueNode(new RawValue(ser));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Constructs an underlying {@link ObjectMapper} used to mask or unmask content.
     */
    public ObjectMapper buildObjectMapper() {
        final List<FieldMaskerSelector<?>> fieldMaskerSelectors = maskerListBuilder.build();
        final ObjectMapper objectMapper = JacksonUtil.newDefaultObjectMapper();
        for (FieldMaskerSelectorProvider<?> customizer : customizersMap.values()) {
            applyProvider(customizer, objectMapper, fieldMaskerSelectors);
        }
        return objectMapper;
    }

    @SuppressWarnings("unchecked")
    private static <T extends FieldMaskerSelector<?>> void applyProvider(
            FieldMaskerSelectorProvider<T> provider, ObjectMapper objectMapper,
            List<FieldMaskerSelector<?>> selectors) {
        final List<T> filtered =
                selectors.stream().filter(selector -> provider.supportedType().isInstance(selector))
                         .map(selector -> (T) selector)
                         .collect(ImmutableList.toImmutableList());
        provider.customize(filtered, objectMapper);
    }
}
