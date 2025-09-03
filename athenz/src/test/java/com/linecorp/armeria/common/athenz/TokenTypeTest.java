/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.athenz;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenTypeTest {

    @Test
    void testAccessToken() {
        final TokenType tokenType = TokenType.ACCESS_TOKEN;
        assertThat(tokenType.headerName().toString()).isEqualToIgnoringCase("Authorization");
        assertThat(tokenType.isRoleToken()).isFalse();
        assertThat(tokenType.authScheme()).isEqualTo("Bearer");
    }

    @Test
    void testYahooRoleToken() {
        final TokenType tokenType = TokenType.YAHOO_ROLE_TOKEN;
        assertThat(tokenType.headerName().toString()).isEqualToIgnoringCase("Yahoo-Role-Auth");
        assertThat(tokenType.isRoleToken()).isTrue();
        assertThat(tokenType.authScheme()).isNull();
    }

    @Test
    void testAthenzRoleToken() {
        final TokenType tokenType = TokenType.ATHENZ_ROLE_TOKEN;
        assertThat(tokenType.headerName().toString()).isEqualToIgnoringCase("Athenz-Role-Auth");
        assertThat(tokenType.isRoleToken()).isTrue();
        assertThat(tokenType.authScheme()).isNull();
    }
}
