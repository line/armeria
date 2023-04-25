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
package com.linecorp.armeria.spring.web.reactive;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test_armeria_ssl_custom_key_over_spring")
class ReactiveWebServerArmeriaCustomKeyOverSpringTest extends AbstractReactiveWebServerCustomKeyAliasTest {

    @SpringBootApplication
    static class TestConfiguration {}

    ReactiveWebServerArmeriaCustomKeyOverSpringTest() {
        // The entry 'key1' contains the self-signed certificate for 'a.com'.
        // For the complete list of the keystore entries, enter the following command:
        //
        //     keytool -list -v -keystore keystore_with_two_keys.pkcs12 -storepass mystorepass
        //
        super("CN=a.com,OU=Unknown,O=Unknown,L=Unknown,ST=Unknown,C=Unknown");
    }
}
