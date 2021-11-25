/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.util;

import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides the common actions that are useful when handling a {@link CompletionStage}.
 */
public final class CompletionActions {

    private static final Logger logger = LoggerFactory.getLogger(CompletionActions.class);

    /**
     * Logs the specified {@link Throwable}. For example:
     * <pre>{@code
     * CompletableFuture<?> f = ...;
     * f.exceptionally(CompletionActions::log);
     * }</pre>
     *
     * @return {@code null}
     */
    @Nullable
    public static <T> T log(Throwable cause) {
        logger.warn("Unexpected exception from a completion action:", cause);
        return null;
    }

    private CompletionActions() {}
}
