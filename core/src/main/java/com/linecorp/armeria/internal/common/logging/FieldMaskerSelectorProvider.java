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

package com.linecorp.armeria.internal.common.logging;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.ContentSanitizer;
import com.linecorp.armeria.common.logging.FieldMaskerSelector;

/**
 * Customizes the {@link ObjectMapper} used by {@link ContentSanitizer}.
 */
@UnstableApi
public interface FieldMaskerSelectorProvider<T extends FieldMaskerSelector<?>> {

    /**
     * The type of {@link FieldMaskerSelector}s this {@link FieldMaskerSelectorProvider} will handle.
     */
    Class<T> supportedType();

    /**
     * Customizes an {@link ObjectMapper} based on the provided {@link FieldMaskerSelector}s.
     * @param fieldMaskerSelectors a list of selectors supported by this {@link FieldMaskerSelectorProvider}.
     * @param objectMapper the {@link ObjectMapper} which will be customized.
     */
    void customize(List<T> fieldMaskerSelectors, ObjectMapper objectMapper);
}
