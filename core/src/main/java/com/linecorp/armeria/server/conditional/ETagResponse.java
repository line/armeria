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

package com.linecorp.armeria.server.conditional;

/**
 * The evaluation result of a conditional request rule.
 */
public enum ETagResponse {
    /** Must perform method. */
    PERFORM_METHOD,
    /**
     * Must NOT perform the method.
     * Mostly for GET/HEAD.
     */
    SKIP_METHOD_NOT_MODIFIED,
    /**
     * Must NOT perform the method.
     * Mostly for PUT/POST/DELETE.
     * May return 200 OK if the state sent is identical
     * to the current state on record.
     */
    SKIP_METHOD_PRECONDITION_FAILED,
}
