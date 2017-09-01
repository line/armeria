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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.PathMapping.ofCatchAll;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class CatchAllPathMappingTest {
    @Test
    public void testLoggerName() throws Exception {
        assertThat(ofCatchAll().loggerName()).isEqualTo("__ROOT__");
    }

    @Test
    public void testMetricName() throws Exception {
        assertThat(ofCatchAll().meterTag()).isEqualTo("catch-all");
    }
}
