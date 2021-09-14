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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.security.credential.Credential;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.saml.SamlServiceProviderBuilder.CredentialResolverAdapter;

/**
 * A builder which builds a {@link SamlIdentityProviderConfig}.
 */
public final class SamlIdentityProviderConfigBuilder {

    private static final SamlNameIdPolicy defaultNameIdPolicy =
            SamlNameIdPolicy.ofCreatable(SamlNameIdFormat.EMAIL);

    private final SamlServiceProviderBuilder parent;

    // IdP(Identity Provider) related:
    @Nullable
    private String entityId;
    @Nullable
    private String signingKey;
    @Nullable
    private String encryptionKey;
    @Nullable
    private SamlEndpoint ssoEndpoint;
    @Nullable
    private SamlEndpoint sloReqEndpoint;
    @Nullable
    private SamlEndpoint sloResEndpoint;

    // SP(Service Provider) related:
    @Nullable
    private SamlEndpoint acsEndpoint;

    // AuthnRequest related:
    private SamlNameIdPolicy nameIdPolicy = defaultNameIdPolicy;

    private boolean isDefault;

    SamlIdentityProviderConfigBuilder(SamlServiceProviderBuilder parent) {
        this.parent = parent;
    }

    /**
     * Sets an entity ID for an identity provider.
     */
    public SamlIdentityProviderConfigBuilder entityId(String entityId) {
        this.entityId = requireNonNull(entityId, "entityId");
        return this;
    }

    /**
     * Sets a {@code signing} key name for an identity provider.
     */
    public SamlIdentityProviderConfigBuilder signingKey(String signingKey) {
        this.signingKey = requireNonNull(signingKey, "signingKey");
        return this;
    }

    /**
     * Sets an {@code encryption} key name for an identity provider.
     */
    public SamlIdentityProviderConfigBuilder encryptionKey(String encryptionKey) {
        this.encryptionKey = requireNonNull(encryptionKey, "encryptionKey");
        return this;
    }

    /**
     * Sets a single sign-on endpoint of an identity provider.
     */
    public SamlIdentityProviderConfigBuilder ssoEndpoint(SamlEndpoint ssoEndpoint) {
        this.ssoEndpoint = requireNonNull(ssoEndpoint, "ssoEndpoint");
        return this;
    }

    /**
     * Sets a single logout request endpoint of an identity provider.
     */
    public SamlIdentityProviderConfigBuilder sloReqEndpoint(SamlEndpoint sloReqEndpoint) {
        this.sloReqEndpoint = requireNonNull(sloReqEndpoint, "sloReqEndpoint");
        return this;
    }

    /**
     * Sets a single logout response endpoint of an identity provider.
     */
    public SamlIdentityProviderConfigBuilder sloResEndpoint(SamlEndpoint sloResEndpoint) {
        this.sloResEndpoint = requireNonNull(sloResEndpoint, "sloResEndpoint");
        return this;
    }

    /**
     * Returns a {@link SamlEndpoint} of the service provider for receiving an assertion from an identity
     * provider.
     */
    @Nullable
    SamlEndpoint acsEndpoint() {
        return acsEndpoint;
    }

    /**
     * Sets an assertion consumer service URL of this service provider.
     */
    public SamlIdentityProviderConfigBuilder acsEndpoint(SamlEndpoint acsEndpoint) {
        this.acsEndpoint = requireNonNull(acsEndpoint, "acsEndpoint");
        return this;
    }

    /**
     * Sets a {@link SamlNameIdPolicy} to configure an {@link AuthnRequest}.
     */
    public SamlIdentityProviderConfigBuilder nameIdPolicy(SamlNameIdPolicy nameIdPolicy) {
        this.nameIdPolicy = requireNonNull(nameIdPolicy, "nameIdPolicy");
        return this;
    }

    /**
     * Returns whether the identity provider is set as a default.
     */
    boolean isDefault() {
        return isDefault;
    }

    /**
     * Sets this idp as a default.
     */
    public SamlIdentityProviderConfigBuilder asDefault() {
        isDefault = true;
        return this;
    }

    /**
     * Returns a {@link SamlServiceProvider} which is the parent of this builder.
     */
    public SamlServiceProviderBuilder and() {
        return parent;
    }

    /**
     * Builds a {@link SamlIdentityProviderConfig}.
     */
    SamlIdentityProviderConfig build(CredentialResolverAdapter credentialResolver) {
        checkState(entityId != null, "entity ID of the identity provider is not set");

        // Use the entityId as a default key name.
        final Credential signing = credentialResolver.apply(firstNonNull(signingKey, entityId));
        final Credential encryption = credentialResolver.apply(firstNonNull(encryptionKey, entityId));

        return new SamlIdentityProviderConfig(entityId,
                                              signing,
                                              encryption,
                                              ssoEndpoint,
                                              sloReqEndpoint,
                                              sloResEndpoint,
                                              acsEndpoint,
                                              nameIdPolicy);
    }
}
