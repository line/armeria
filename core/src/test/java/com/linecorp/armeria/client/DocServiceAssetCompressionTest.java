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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.server.docs.DocService;

class DocServiceAssetCompressionTest {

    @Test
    void shouldNotIncludeUncompressedAssets() {
        // `doc-client` should produce compressed assets when building bundle files for DocService and
        // they should exist in the classpath of the core module.
        // If Gradle build task is executed with `-PnoWeb`, this test may be broken.
        final String file = "index.html";
        assertThat(DocService.class.getResource(file)).isNull();
        assertThat(DocService.class.getResource(file + ".gz")).isNotNull();
    }
}
