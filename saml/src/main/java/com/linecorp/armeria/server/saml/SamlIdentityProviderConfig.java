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

import static java.util.Objects.requireNonNull;

import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.security.credential.Credential;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A configuration for an identity provider.
 */
public final class SamlIdentityProviderConfig {

    // IdP(Identity Provider) related:
    private final String entityId;
    private final Credential signingCredential;
    private final Credential encryptionCredential;
    private final SamlEndpoint ssoEndpoint;
    @Nullable
    private final SamlEndpoint sloReqEndpoint;
    @Nullable
    private final SamlEndpoint sloResEndpoint;

    // SP(Service Provider) related:
    @Nullable
    private final SamlEndpoint acsEndpoint;

    // AuthnRequest related:
    private final SamlNameIdPolicy nameIdPolicy;

    SamlIdentityProviderConfig(String entityId,
                               Credential signingCredential,
                               Credential encryptionCredential,
                               SamlEndpoint ssoEndpoint,
                               @Nullable SamlEndpoint sloReqEndpoint,
                               @Nullable SamlEndpoint sloResEndpoint,
                               @Nullable SamlEndpoint acsEndpoint,
                               SamlNameIdPolicy nameIdPolicy) {
        this.entityId = requireNonNull(entityId, "entityId");
        this.signingCredential = requireNonNull(signingCredential, "signingCredential");
        this.encryptionCredential = requireNonNull(encryptionCredential, "encryptionCredential");
        this.ssoEndpoint = requireNonNull(ssoEndpoint, "ssoEndpoint");
        this.sloReqEndpoint = sloReqEndpoint;
        this.sloResEndpoint = sloResEndpoint;
        this.acsEndpoint = acsEndpoint;
        this.nameIdPolicy = requireNonNull(nameIdPolicy, "nameIdPolicy");
    }

    /**
     * Returns an entity ID of the identity provider.
     */
    public String entityId() {
        return entityId;
    }

    /**
     * Returns a {@link Credential} of the identity provider for signing.
     */
    public Credential signingCredential() {
        return signingCredential;
    }

    /**
     * Returns a {@link Credential} of the identity provider for encryption.
     */
    public Credential encryptionCredential() {
        return encryptionCredential;
    }

    /**
     * Returns a {@link SamlEndpoint} of the identity provider for receiving an authentication request.
     */
    public SamlEndpoint ssoEndpoint() {
        return ssoEndpoint;
    }

    /**
     * Returns a {@link SamlEndpoint} of the identity provider for receiving a single logout request.
     */
    @Nullable
    public SamlEndpoint sloReqEndpoint() {
        return sloReqEndpoint;
    }

    /**
     * Returns a {@link SamlEndpoint} of the identity provider for receiving a single logout response.
     */
    @Nullable
    public SamlEndpoint sloResEndpoint() {
        return sloResEndpoint;
    }

    /**
     * Returns a {@link SamlEndpoint} of the service provider that the assertion will be sent to in response
     * to the authentication request.
     */
    @Nullable
    public SamlEndpoint acsEndpoint() {
        return acsEndpoint;
    }

    /**
     * Returns a {@link NameIDPolicy} of the service provider which is sent to the identity provider via
     * an authentication request.
     */
    public SamlNameIdPolicy nameIdPolicy() {
        return nameIdPolicy;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("entityId", entityId)
                          .add("signingCredential", signingCredential)
                          .add("encryptionCredential", encryptionCredential)
                          .add("ssoEndpoint", ssoEndpoint)
                          .add("sloReqEndpoint", sloReqEndpoint)
                          .add("sloResEndpoint", sloResEndpoint)
                          .add("acsEndpoint", acsEndpoint)
                          .add("nameIdPolicy", nameIdPolicy)
                          .toString();
    }
}
