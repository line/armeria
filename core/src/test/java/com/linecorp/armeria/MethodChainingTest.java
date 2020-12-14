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

package com.linecorp.armeria;

import com.linecorp.armeria.internal.testing.AbstractMethodChainingTest;

class MethodChainingTest extends AbstractMethodChainingTest {

    MethodChainingTest() {
        super("com.linecorp.armeria.common.DefaultQueryParamsBuilder",
              "com.linecorp.armeria.common.DefaultRequestHeadersBuilder",
              "com.linecorp.armeria.common.DefaultHttpHeadersBuilder",
              "com.linecorp.armeria.common.DefaultResponseHeadersBuilder",
              "com.linecorp.armeria.client.ClientBuilder",
              "com.linecorp.armeria.server.file.HttpFileBuilder$FileSystemHttpFileBuilder",
              "com.linecorp.armeria.server.file.HttpFileBuilder$ClassPathHttpFileBuilder",
              "com.linecorp.armeria.server.file.HttpFileBuilder$NonExistentHttpFileBuilder",
              "com.linecorp.armeria.server.file.HttpFileBuilder$HttpDataFileBuilder",
              "com.linecorp.armeria.server.DomainMappingBuilder");
    }
}
