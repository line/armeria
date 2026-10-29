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
 * SAML name ID formats.
 */
public enum SamlNameIdFormat {
    /**
     * Unspecified name format.
     */
    UNSPECIFIED("urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified"),

    /**
     * Email name format.
     */
    EMAIL("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"),

    /**
     * X509 subject name format.
     */
    X509_SUBJECT("urn:oasis:names:tc:SAML:1.1:nameid-format:X509SubjectName"),

    /**
     * Windows domain qualified name format.
     */
    WIN_DOMAIN_QUALIFIED("urn:oasis:names:tc:SAML:1.1:nameid-format:WindowsDomainQualifiedName"),

    /**
     * Kerberos name format.
     */
    KERBEROS("urn:oasis:names:tc:SAML:2.0:nameid-format:kerberos"),

    /**
     * SAML entity name format.
     */
    ENTITY("urn:oasis:names:tc:SAML:2.0:nameid-format:entity"),

    /**
     * Persistent name format.
     */
    PERSISTENT("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"),

    /**
     * Transient name format.
     */
    TRANSIENT("urn:oasis:names:tc:SAML:2.0:nameid-format:transient"),

    /**
     * Used by NameIDPolicy to indicate a NameID should be encrypted.
     */
    ENCRYPTED("urn:oasis:names:tc:SAML:2.0:nameid-format:encrypted");

    private final String urn;

    SamlNameIdFormat(String urn) {
        this.urn = urn;
    }

    /**
     * Returns the URN of this name ID format.
     */
    public String urn() {
        return urn;
    }
}
