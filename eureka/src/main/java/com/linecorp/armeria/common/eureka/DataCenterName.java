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
package com.linecorp.armeria.common.eureka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The data center names.
 */
public enum DataCenterName {
    Netflix,
    Amazon,
    MyOwn;

    private static final Logger logger = LoggerFactory.getLogger(DataCenterName.class);

    /**
     * Returns the {@link Enum} value corresponding to the specified {@code str}.
     * {@link #MyOwn} is returned if none of {@link Enum}s are matched.
     */
    public static DataCenterName toEnum(String str) {
        try {
            return valueOf(str);
        } catch (IllegalArgumentException e) {
            logger.warn("unknown enum value: {} (expected: {}), {} is set by default. ", str, values(), MyOwn);
        }
        return MyOwn;
    }
}
