/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.logging.kafka;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestOnlyLog;

/**
 * A predicate that determines whether a request log should be written or not.
 * This predicate can be used by {@link KafkaLogWriter#logRequest(RequestOnlyLog)}.
 */
@FunctionalInterface
@UnstableApi
public interface KafkaRequestLogOutputPredicate {

    /**
     * Returns a {@link KafkaRequestLogOutputPredicate} that will always return {@code false}.
     */
    static KafkaRequestLogOutputPredicate never() {
        return log -> false;
    }

    /**
     * Returns a {@link KafkaRequestLogOutputPredicate} that will always return {@code true}.
     */
    static KafkaRequestLogOutputPredicate always() {
        return log -> true;
    }

    /**
     * Returns {@code true} when the request log need to be written.
     */
    boolean test(RequestOnlyLog log);
}
