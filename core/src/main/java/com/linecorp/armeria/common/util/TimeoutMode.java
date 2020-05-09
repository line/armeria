/*
 * Copyright 2020 LINE Corporation
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

import com.linecorp.armeria.common.Request;

/**
 * The timeout mode.
 */
public enum TimeoutMode {
    /**
     * Schedules a timeout from the current time.
     */
    SET_FROM_NOW,
    /**
     * Schedules a timeout from the start time of the {@link Request}.
     */
    SET_FROM_START,
    /**
     * Extends the previously scheduled timeout.
     */
    EXTEND
}
