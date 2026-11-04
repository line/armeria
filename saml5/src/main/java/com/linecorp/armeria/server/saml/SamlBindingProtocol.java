/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

/**
 * SAML binding protocols. Only HTTP Redirect and HTTP POST binding protocols are supported.
 * The other protocols, such as HTTP Artifact binding protocol, are not supported yet.
 */
public enum SamlBindingProtocol {
    /**
     * HTTP Redirect binding protocol.
     */
    HTTP_REDIRECT("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"),

    /**
     * HTTP POST binding protocol.
     */
    HTTP_POST("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");

    private final String urn;

    SamlBindingProtocol(String urn) {
        this.urn = urn;
    }

    /**
     * Returns the URN of this binding protocol.
     */
    public String urn() {
        return urn;
    }
}
