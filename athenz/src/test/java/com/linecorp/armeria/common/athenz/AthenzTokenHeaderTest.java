/*
 * Copyright 2026 LY Corporation
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

import io.netty.util.AsciiString;

class AthenzTokenHeaderTest {

    @Test
    void tokenTypeImplementsAthenzTokenHeader() {
        // TokenType enum should implement AthenzTokenHeader
        final AthenzTokenHeader accessToken = TokenType.ACCESS_TOKEN;
        assertThat(accessToken.name()).isEqualTo("ACCESS_TOKEN");
        assertThat(accessToken.headerName().toString()).isEqualToIgnoringCase("Authorization");
        assertThat(accessToken.authScheme()).isEqualTo("Bearer");
        assertThat(accessToken.isRoleToken()).isFalse();

        final AthenzTokenHeader athenzRoleToken = TokenType.ATHENZ_ROLE_TOKEN;
        assertThat(athenzRoleToken.name()).isEqualTo("ATHENZ_ROLE_TOKEN");
        assertThat(athenzRoleToken.headerName().toString()).isEqualToIgnoringCase("Athenz-Role-Auth");
        assertThat(athenzRoleToken.authScheme()).isNull();
        assertThat(athenzRoleToken.isRoleToken()).isTrue();

        final AthenzTokenHeader yahooRoleToken = TokenType.YAHOO_ROLE_TOKEN;
        assertThat(yahooRoleToken.name()).isEqualTo("YAHOO_ROLE_TOKEN");
        assertThat(yahooRoleToken.headerName().toString()).isEqualToIgnoringCase("Yahoo-Role-Auth");
        assertThat(yahooRoleToken.authScheme()).isNull();
        assertThat(yahooRoleToken.isRoleToken()).isTrue();
    }

    @Test
    void customImplementation() {
        final AthenzTokenHeader customHeader = new CustomAthenzHeader("X-Company-Token");
        
        assertThat(customHeader.name()).isEqualTo("CUSTOM_X_COMPANY_TOKEN");
        assertThat(customHeader.headerName().toString()).isEqualToIgnoringCase("X-Company-Token");
        assertThat(customHeader.authScheme()).isNull();
        assertThat(customHeader.isRoleToken()).isFalse();
    }

    @Test
    void customImplementationWithAuthScheme() {
        final AthenzTokenHeader customHeader = new CustomHeaderWithScheme("X-Custom-Auth", "CustomBearer");
        
        assertThat(customHeader.name()).isEqualTo("CUSTOM_AUTH");
        assertThat(customHeader.headerName().toString()).isEqualToIgnoringCase("X-Custom-Auth");
        assertThat(customHeader.authScheme()).isEqualTo("CustomBearer");
        assertThat(customHeader.isRoleToken()).isFalse();
    }

    // Example custom implementation from JavaDoc
    private static final class CustomAthenzHeader implements AthenzTokenHeader {
        private final String headerName;
        private final AsciiString asciiHeaderName;

        CustomAthenzHeader(String headerName) {
            this.headerName = headerName;
            asciiHeaderName = AsciiString.of(headerName);
        }

        @Override
        public String name() {
            return "CUSTOM_" + headerName.toUpperCase().replace('-', '_');
        }

        @Override
        public AsciiString headerName() {
            return asciiHeaderName;
        }

        @Override
        public String authScheme() {
            return null;
        }

        @Override
        public boolean isRoleToken() {
            return false;
        }
    }

    // Custom implementation with auth scheme
    private static final class CustomHeaderWithScheme implements AthenzTokenHeader {
        private final String name;
        private final AsciiString headerName;
        private final String authScheme;

        CustomHeaderWithScheme(String headerName, String authScheme) {
            this.name = headerName.toUpperCase().replace("X-", "").replace('-', '_');
            this.headerName = AsciiString.of(headerName);
            this.authScheme = authScheme;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AsciiString headerName() {
            return headerName;
        }

        @Override
        public String authScheme() {
            return authScheme;
        }

        @Override
        public boolean isRoleToken() {
            return false;
        }
    }
}
