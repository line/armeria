/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.logging.LogFormatter
import org.junit.jupiter.api.Test

class Jsr305StrictTest {
    @Test
    fun shouldAllowReturningNulls() {
        // Make sure the code compiles with `-Xjsr305=strict`
        // See: https://github.com/line/armeria/issues/2793#issuecomment-892325587
        LogFormatter.builderForText()
            .requestHeadersSanitizer { _, _ -> null }
            .responseHeadersSanitizer { _, _ -> null }
            .requestTrailersSanitizer { _, _ -> null }
            .responseTrailersSanitizer { _, _ -> null }
            .headersSanitizer { _, _ -> null }
            .requestContentSanitizer { _, _ -> null }
            .responseContentSanitizer { _, _ -> null }
            .contentSanitizer { _, _ -> null }

        LogFormatter.builderForJson()
            .requestHeadersSanitizer { _, _ -> null }
            .responseHeadersSanitizer { _, _ -> null }
            .requestTrailersSanitizer { _, _ -> null }
            .responseTrailersSanitizer { _, _ -> null }
            .headersSanitizer { _, _ -> null }
            .requestContentSanitizer { _, _ -> null }
            .responseContentSanitizer { _, _ -> null }
            .contentSanitizer { _, _ -> null }
    }
}
