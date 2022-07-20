/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.Description;

/**
 * Supported markup types in {@link Description}.
 */
@UnstableApi
public enum Markup {
    /**
     * No markup.
     */
    NONE,
    /**
     * <a href="https://en.wikipedia.org/wiki/Markdown">Markdown</a>.
     */
    MARKDOWN,
    /**
     * <a href="https://mermaid-js.github.io/mermaid/#/">Mermaid</a>.
     */
    MERMAID
}
