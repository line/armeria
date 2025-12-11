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
/*
 * Copyright The Athenz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.server.athenz;

import static com.yahoo.athenz.zpe.ZpeConsts.ZPE_PROP_MILLIS_BETWEEN_ZTS_CALLS;

import java.net.URI;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.oath.auth.KeyRefresher;
import com.oath.auth.Utils;
import com.yahoo.athenz.auth.token.AccessToken;
import com.yahoo.athenz.auth.token.RoleToken;
import com.yahoo.athenz.auth.token.jwts.JwtsSigningKeyResolver;
import com.yahoo.athenz.auth.util.CryptoException;
import com.yahoo.athenz.zpe.ZpeClient;
import com.yahoo.athenz.zpe.ZpeConsts;
import com.yahoo.athenz.zpe.match.ZpeMatch;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.rdl.Struct;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.ClientTlsSpecBuilder;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.JdkSslContext;

final class MinifiedAuthZpeClient {

    // Forked from https://github.com/AthenZ/athenz/blob/3acc0ea0e0f44adc0fb69bb442a0a449b655ad10/clients/java/zpe/src/main/java/com/yahoo/athenz/zpe/AuthZpeClient.java
    // Changes made:
    // - Added the constructor to configure a custom PublicKeyStore and ZpeClient per instance
    // - Removed unused methods and fields
    // - Changed static methods to instance methods
    // - Changed access modifiers to package-private or private as appropriate
    // - Lowered the logging level from error to warn

    // TODO(ikhoon): Refactor MinifiedAuthZpeClient to perform access check asynchronously.

    private static final Logger logger = LoggerFactory.getLogger(MinifiedAuthZpeClient.class);

    private static final String BEARER_TOKEN = "Bearer ";

    private int allowedOffset = 300;
    private JwtsSigningKeyResolver accessSignKeyResolver;
    private final ZpeClient zpeClt;
    private final PublicKeyStore publicKeyStore;

    public enum AccessCheckStatus {
        ALLOW {
            @Override
            public String toString() {
                return "Access Check was explicitly allowed";
            }
        },
        DENY {
            @Override
            public String toString() {
                return "Access Check was explicitly denied";
            }
        },
        DENY_NO_MATCH {
            @Override
            public String toString() {
                return "Access denied due to no match to any of the assertions defined in domain policy file";
            }
        },
        DENY_ROLETOKEN_EXPIRED {
            @Override
            public String toString() {
                return "Access denied due to expired Token";
            }
        },
        DENY_ROLETOKEN_INVALID {
            @Override
            public String toString() {
                return "Access denied due to invalid Token";
            }
        },
        DENY_DOMAIN_MISMATCH {
            @Override
            public String toString() {
                return "Access denied due to domain mismatch between Resource and Token";
            }
        },
        DENY_DOMAIN_NOT_FOUND {
            @Override
            public String toString() {
                return "Access denied due to domain not found in library cache";
            }
        },
        DENY_DOMAIN_EXPIRED {
            @Override
            public String toString() {
                return "Access denied due to expired domain policy file";
            }
        },
        DENY_DOMAIN_EMPTY {
            @Override
            public String toString() {
                return "Access denied due to no policies in the domain file";
            }
        },
        DENY_INVALID_PARAMETERS {
            @Override
            public String toString() {
                return "Access denied due to invalid/empty action/resource values";
            }
        },
        DENY_CERT_MISMATCH_ISSUER {
            @Override
            public String toString() {
                return "Access denied due to certificate mismatch in issuer";
            }
        },
        DENY_CERT_MISSING_SUBJECT {
            @Override
            public String toString() {
                return "Access denied due to missing subject in certificate";
            }
        },
        DENY_CERT_MISSING_DOMAIN {
            @Override
            public String toString() {
                return "Access denied due to missing domain name in certificate";
            }
        },
        DENY_CERT_MISSING_ROLE_NAME {
            @Override
            public String toString() {
                return "Access denied due to missing role name in certificate";
            }
        },
        DENY_CERT_HASH_MISMATCH {
            @Override
            public String toString() {
                return "Access denied due to access token certificate hash mismatch";
            }
        }
    }

    MinifiedAuthZpeClient(ZtsBaseClient ztsBaseClient, PublicKeyStore publicKeyStore, ZpeClient zpeClt,
                          String oauth2KeysPath) {
        this.publicKeyStore = publicKeyStore;
        this.zpeClt = zpeClt;

        // set the allowed offset

        setTokenAllowedOffset(Integer.parseInt(System.getProperty(ZpeConsts.ZPE_PROP_TOKEN_OFFSET, "300")));

        // initialize the access token signing key resolver

        initializeAccessTokenSignKeyResolver(ztsBaseClient, oauth2KeysPath);

        // save the last zts api call time, and the allowed interval between api calls

        setMillisBetweenZtsCalls(Long.parseLong(
                System.getProperty(ZPE_PROP_MILLIS_BETWEEN_ZTS_CALLS, Long.toString(30 * 1000 * 60))));
    }

    private void initializeAccessTokenSignKeyResolver(ZtsBaseClient ztsBaseClient, String oauth2KeysPath) {
        final String serverUrl = System.getProperty(ZpeConsts.ZPE_PROP_JWK_URI);
        if (serverUrl == null || serverUrl.isEmpty()) {
            accessSignKeyResolver = newDefaultJwtsSigningKeyResolver(ztsBaseClient, oauth2KeysPath);
            ztsBaseClient.addTlsKeyPairListener(tlsKeyPair -> {
                // Refresh the JwtsSigningKeyResolver when the TLS key pair changes
                accessSignKeyResolver = newDefaultJwtsSigningKeyResolver(ztsBaseClient, oauth2KeysPath);
            });
            return;
        }

        final String keyPath = System.getProperty(ZpeConsts.ZPE_PROP_JWK_PRIVATE_KEY_PATH);
        final String certPath = System.getProperty(ZpeConsts.ZPE_PROP_JWK_X509_CERT_PATH);
        SSLContext sslContext = null;
        if (keyPath != null && !keyPath.isEmpty() && certPath != null && !certPath.isEmpty()) {
            try {
                final KeyRefresher keyRefresher = Utils.generateKeyRefresher(null, certPath, keyPath);
                keyRefresher.startup();
                sslContext = Utils.buildSSLContext(keyRefresher.getKeyManagerProxy(),
                                                   keyRefresher.getTrustManagerProxy());
            } catch (Exception ex) {
                logger.warn("Unable to initialize key refresher: {}", ex.getMessage());
            }
        }
        final URI proxyUri = ztsBaseClient.proxyUri();
        accessSignKeyResolver = new JwtsSigningKeyResolver(serverUrl, sslContext,
                                                           proxyUri != null ? proxyUri.toString() : null);
    }

    private static JwtsSigningKeyResolver newDefaultJwtsSigningKeyResolver(ZtsBaseClient ztsBaseClient,
                                                                           String oauth2KeysPath) {
        final URI ztsUri = ztsBaseClient.ztsUri();
        final URI proxyUri = ztsBaseClient.proxyUri();
        String proxyUriStr = null;
        if (proxyUri != null) {
            proxyUriStr = proxyUri.toString();
        }
        final ClientFactory clientFactory = ztsBaseClient.clientFactory();
        final TlsProvider tlsProvider = clientFactory.options().tlsProvider();
        final ClientTlsSpec clientTlsSpec = toTlsSpec(tlsProvider);
        final boolean allowUnsafeCiphers = clientFactory.options().tlsConfig().allowsUnsafeCiphers();
        final JdkSslContext sslContext =
                (JdkSslContext) SslContextUtil.toSslContext(clientTlsSpec, allowUnsafeCiphers);
        return new JwtsSigningKeyResolver(ztsUri + oauth2KeysPath, sslContext.context(), proxyUriStr);
    }

    private static ClientTlsSpec toTlsSpec(TlsProvider tlsProvider) {
        final ClientTlsSpecBuilder builder = ClientTlsSpec.builder();
        final TlsKeyPair tlsKeyPair = tlsProvider.keyPair("*");
        if (tlsKeyPair != null) {
            builder.tlsKeyPair(tlsKeyPair);
        }
        final List<X509Certificate> trustedCertificates = tlsProvider.trustedCertificates("*");
        if (trustedCertificates != null) {
            builder.trustedCertificates(trustedCertificates);
        }
        builder.engineType(TlsEngineType.JDK);
        return builder.build();
    }

    /**
     * Set the role token allowed offset. this might be necessary
     * if the client and server are not ntp synchronized, and we
     * don't want the server to reject valid role tokens
     * @param offset value in seconds
     */
    void setTokenAllowedOffset(int offset) {
        // skip any invalid values
        if (offset > 0) {
            allowedOffset = offset;
        }
    }

    PublicKey getZtsPublicKey(String keyId) {
        PublicKey publicKey = publicKeyStore.getZtsKey(keyId);
        if (publicKey == null) {
            //  fetch all zts jwk keys and update config and try again
            publicKey = accessSignKeyResolver.getPublicKey(keyId);
        }
        return publicKey;
    }

    private void setMillisBetweenZtsCalls(long millis) {
        accessSignKeyResolver.setMillisBetweenZtsCalls(millis);
    }

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the user (cltToken, cltTokenName).
     *
     * @param token - either role or access token. For role tokens:
     *        value for the HTTP header: Athenz-Role-Auth
     *        ex: "v=Z1;d=angler;r=admin;a=aAkjbbDMhnLX;t=1431974053;e=1431974153;k=0"
     *        For access tokens: value for HTTP header: Authorization: Bearer access-token
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *        the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *        reason why the access was denied
     */
    AccessCheckStatus allowAccess(String token, String resource, String action) {
        final StringBuilder matchRoleName = new StringBuilder(256);
        return allowAccess(token, null, null, resource, action, matchRoleName);
    }

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the user (cltToken, cltTokenName).
     * @param token either role or access token. For role tokens:
     *        value for the HTTP header: Athenz-Role-Auth
     *        ex: "v=Z1;d=angler;r=admin;a=aAkjbbDMhnLX;t=1431974053;e=1431974153;k=0"
     *        For access tokens: value for HTTP header: Authorization: Bearer access-token
     * @param cert X509 Client Certificate used to establish the mTLS connection
     *        submitting this request
     * @param certHash If the connection is coming through a proxy, this includes
     *        the certificate hash of the client certificate that was calculated
     *        by the proxy and forwarded in a http header
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     * @param matchRoleName - [out] will include the role name that the result was based on
     *        it will be not be set if the failure is due to expired/invalid tokens or
     *        there were no matches thus a default value of DENY_NO_MATCH is returned
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *        the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *        reason why the access was denied
     */
    private AccessCheckStatus allowAccess(String token, @Nullable X509Certificate cert,
                                          @Nullable String certHash,
                                          String resource, String action, StringBuilder matchRoleName) {

        if (logger.isDebugEnabled()) {
            logger.debug("allowAccess: action={} resource={}", action, resource);
        }

        // check if we're given role or access token

        if (token.startsWith("v=Z1;")) {
            return allowRoleTokenAccess(token, resource, action, matchRoleName);
        } else {
            return allowAccessTokenAccess(token, cert, certHash, resource, action, matchRoleName);
        }
    }

    private AccessCheckStatus allowRoleTokenAccess(String roleToken, String resource, String action,
                                                   StringBuilder matchRoleName) {

        final Map<String, RoleToken> tokenCache = zpeClt.getRoleTokenCacheMap();
        RoleToken rToken = tokenCache.get(roleToken);

        if (rToken == null) {

            rToken = new RoleToken(roleToken);

            // validate the token. validation also verifies that
            // the token is not expired

            if (!rToken.validate(getZtsPublicKey(rToken.getKeyId()), allowedOffset, false, null)) {

                // check the token expiration and provide a more specific
                // status code to the caller

                if (isTokenExpired(rToken)) {
                    return AccessCheckStatus.DENY_ROLETOKEN_EXPIRED;
                }

                logger.warn("allowAccess: Authorization denied. Authentication failed for token={}",
                            rToken.getUnsignedToken());
                return AccessCheckStatus.DENY_ROLETOKEN_INVALID;
            }

            addTokenToCache(tokenCache, roleToken, rToken);
        }

        return allowAccess(rToken, resource, action, matchRoleName);
    }

    private AccessCheckStatus allowAccessTokenAccess(String accessToken, @Nullable X509Certificate cert,
                                                     @Nullable String certHash,
                                                     String resource, String action,
                                                     StringBuilder matchRoleName) {

        // if our client sent the full header including Bearer part
        // we're going to strip that out

        if (accessToken.startsWith(BEARER_TOKEN)) {
            accessToken = accessToken.substring(BEARER_TOKEN.length());
        }

        final Map<String, AccessToken> tokenCache = zpeClt.getAccessTokenCacheMap();
        AccessToken acsToken = tokenCache.get(accessToken);

        // if we have an x.509 certificate provided then we need to
        // validate our mtls client certificate confirmation value
        // before accepting the token from the cache

        if (acsToken != null && cert != null && !acsToken.confirmMTLSBoundToken(cert, certHash)) {
            logger.warn("allowAccess: mTLS Client certificate confirmation failed");
            return AccessCheckStatus.DENY_CERT_HASH_MISMATCH;
        }

        if (acsToken == null) {

            try {
                if (cert == null && certHash == null) {
                    acsToken = new AccessToken(accessToken, accessSignKeyResolver);
                } else {
                    acsToken = new AccessToken(accessToken, accessSignKeyResolver, cert, certHash);
                }
            } catch (CryptoException ex) {

                logger.warn("allowAccess: Authorization denied. Authentication failed for token={}",
                            ex.getMessage());
                return (ex.getCode() == CryptoException.CERT_HASH_MISMATCH) ?
                       AccessCheckStatus.DENY_CERT_HASH_MISMATCH : AccessCheckStatus.DENY_ROLETOKEN_INVALID;
            } catch (Exception ex) {

                logger.warn("allowAccess: Authorization denied. Authentication failed for token={}",
                            ex.getMessage());
                return AccessCheckStatus.DENY_ROLETOKEN_INVALID;
            }

            addTokenToCache(tokenCache, accessToken, acsToken);
        }

        return allowAccess(acsToken, resource, action, matchRoleName);
    }

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the RoleToken.
     * @param rToken represents the role token sent by the client that wants access to the resource
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     * @param matchRoleName - [out] will include the role name that the result was based on
     *        it will be not be set if the failure is due to expired/invalid tokens or
     *        there were no matches thus a default value of DENY_NO_MATCH is returned
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *        the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *        reason why the access was denied
     **/
    @SuppressWarnings("checkstyle:OverloadMethodsDeclarationOrder")
    private AccessCheckStatus allowAccess(RoleToken rToken, String resource, String action,
                                          StringBuilder matchRoleName) {

        // check the token expiration

        if (rToken == null) {
            logger.warn("allowAccess: Authorization denied. Token is null");
            return AccessCheckStatus.DENY_ROLETOKEN_INVALID;
        }

        if (isTokenExpired(rToken)) {
            return AccessCheckStatus.DENY_ROLETOKEN_EXPIRED;
        }

        final String tokenDomain = rToken.getDomain(); // ZToken contains the domain
        final List<String> roles = rToken.getRoles();  // ZToken contains roles

        return allowActionZPE(action, tokenDomain, resource, roles, matchRoleName);
    }

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the AccessToken.
     * @param accessToken represents the access token sent by the client that wants access to the resource
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     * @param matchRoleName - [out] will include the role name that the result was based on
     *        it will be not be set if the failure is due to expired/invalid tokens or
     *        there were no matches thus a default value of DENY_NO_MATCH is returned
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *        the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *        reason why the access was denied
     **/
    private AccessCheckStatus allowAccess(AccessToken accessToken, String resource, String action,
                                          StringBuilder matchRoleName) {

        // check the token expiration

        if (accessToken == null) {
            logger.warn("allowAccess: Authorization denied. Token is null");
            return AccessCheckStatus.DENY_ROLETOKEN_INVALID;
        }

        if (isTokenExpired(accessToken)) {
            return AccessCheckStatus.DENY_ROLETOKEN_EXPIRED;
        }

        final String tokenDomain = accessToken.getAudience();
        final List<String> roles = accessToken.getScope();

        return allowActionZPE(action, tokenDomain, resource, roles, matchRoleName);
    }

    private static boolean isTokenExpired(RoleToken roleToken) {

        final long now = System.currentTimeMillis() / 1000;
        final long expiry = roleToken.getExpiryTime();
        if (expiry != 0 && expiry < now) {
            logger.warn("ExpiryCheck: Token expired. now={} expiry={} token={}",
                        now, expiry, roleToken.getUnsignedToken());
            return true;
        }
        return false;
    }

    private static boolean isTokenExpired(AccessToken accessToken) {

        final long now = System.currentTimeMillis() / 1000;
        final long expiry = accessToken.getExpiryTime();
        if (expiry != 0 && expiry < now) {
            logger.warn("ExpiryCheck: Token expired. now={} expiry={} token={}",
                        now, expiry, accessToken.getClientId());
            return true;
        }
        return false;
    }

    /*
     * Peel off domain name from the assertion string if it matches
     * domain and return the string without the domain prefix.
     * Else, return default value
     */
    @Nullable
    static String stripDomainPrefix(String assertString, String domain, @Nullable String defaultValue) {
        final int index = assertString.indexOf(':');
        if (index == -1) {
            return assertString;
        }

        if (!assertString.substring(0, index).equals(domain)) {
            return defaultValue;
        }

        return assertString.substring(index + 1);
    }

    // check action access in the domain to the resource with the given roles

    /**
     * Determine if access(action) is allowed against the specified resource by
     * a user represented by the given roles. The expected method for authorization
     * check is the allowAccess methods. However, if the client is responsible for
     * validating the role token (including expiration check), it may use this
     * method directly by just specifying the tokenDomain and roles arguments
     * which are directly extracted from the role token.
     * @param action is the type of access attempted by a client
     *        ex: "read"
     *        ex: "scan"
     * @param tokenDomain represents the domain the role token was issued for
     * @param resource is a domain qualified resource the calling service
     *        will check access for.  ex: my_domain:my_resource
     *        ex: "angler:pondsKernCounty"
     *        ex: "sports:service.storage.tenant.Activator.ActionMap"
     * @param roles list of roles extracted from the role token
     * @param matchRoleName - [out] will include the role name that the result was based on
     *        it will be not be set if the failure is due to expired/invalid tokens or
     *        there were no matches thus a default value of DENY_NO_MATCH is returned
     * @return AccessCheckStatus if the user can access the resource via the specified action
     *        the result is ALLOW otherwise one of the DENY_* values specifies the exact
     *        reason why the access was denied
     **/
    private AccessCheckStatus allowActionZPE(String action, String tokenDomain, String resource,
                                             List<String> roles, StringBuilder matchRoleName) {

        final String msgPrefix = "allowActionZPE: domain(" + tokenDomain + ") action(" + action +
                                 ") resource(" + resource + ')';

        if (roles == null || roles.isEmpty()) {
            logger.warn("{} ERROR: No roles so access denied", msgPrefix);
            return AccessCheckStatus.DENY_ROLETOKEN_INVALID;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{} roles({}) starting...", msgPrefix, String.join(",", roles));
        }

        if (tokenDomain == null || tokenDomain.isEmpty()) {
            logger.warn("{} ERROR: No domain so access denied", msgPrefix);
            return AccessCheckStatus.DENY_ROLETOKEN_INVALID;
        }

        if (action == null || action.isEmpty()) {
            logger.warn("{} ERROR: No action so access denied", msgPrefix);
            return AccessCheckStatus.DENY_INVALID_PARAMETERS;
        }
        action = action.toLowerCase();

        if (resource == null || resource.isEmpty()) {
            logger.warn("{} ERROR: No resource so access denied", msgPrefix);
            return AccessCheckStatus.DENY_INVALID_PARAMETERS;
        }
        resource = resource.toLowerCase();

        // Note: if domain in token doesn't match domain in resource then there
        // will be no match of any resource in the assertions - so deny immediately
        // special case - when we have a single domain being processed by ZPE
        // the application will never have generated multiple domain values thus
        // if the resource contains : for something else, we'll ignore it and don't
        // assume it's part of the domain separator and thus reject the request.
        // for multiple domains, if the resource might contain :, it's the responsibility
        // of the caller to include the "domain-name:" prefix as part of the resource

        resource = stripDomainPrefix(resource, tokenDomain, zpeClt.getDomainCount() == 1 ? resource : null);
        if (resource == null) {
            logger.warn("{} ERROR: Domain mismatch in token({}) and resource so access denied",
                        msgPrefix, tokenDomain);
            return AccessCheckStatus.DENY_DOMAIN_MISMATCH;
        }

        // first hunt by role for deny assertions since deny takes precedence
        // over allow assertions

        AccessCheckStatus status = AccessCheckStatus.DENY_DOMAIN_NOT_FOUND;
        Map<String, List<Struct>> roleMap = zpeClt.getRoleDenyAssertions(tokenDomain);
        if (roleMap != null && !roleMap.isEmpty()) {
            if (actionByRole(action, tokenDomain, resource, roles, roleMap, matchRoleName)) {
                return AccessCheckStatus.DENY;
            } else {
                status = AccessCheckStatus.DENY_NO_MATCH;
            }
        } else if (roleMap != null) {
            status = AccessCheckStatus.DENY_DOMAIN_EMPTY;
        }

        // if the check was not explicitly denied by a standard role, then
        // let's process our wildcard roles for deny assertions

        roleMap = zpeClt.getWildcardDenyAssertions(tokenDomain);
        if (roleMap != null && !roleMap.isEmpty()) {
            if (actionByWildCardRole(action, tokenDomain, resource, roles, roleMap, matchRoleName)) {
                return AccessCheckStatus.DENY;
            } else {
                status = AccessCheckStatus.DENY_NO_MATCH;
            }
        } else if (status != AccessCheckStatus.DENY_NO_MATCH && roleMap != null) {
            status = AccessCheckStatus.DENY_DOMAIN_EMPTY;
        }

        // so far it did not match any deny assertions so now let's
        // process our allow assertions

        roleMap = zpeClt.getRoleAllowAssertions(tokenDomain);
        if (roleMap != null && !roleMap.isEmpty()) {
            if (actionByRole(action, tokenDomain, resource, roles, roleMap, matchRoleName)) {
                return AccessCheckStatus.ALLOW;
            } else {
                status = AccessCheckStatus.DENY_NO_MATCH;
            }
        } else if (status != AccessCheckStatus.DENY_NO_MATCH && roleMap != null) {
            status = AccessCheckStatus.DENY_DOMAIN_EMPTY;
        }

        // at this point we either got an allow or didn't match anything so we're
        // going to try the wildcard roles

        roleMap = zpeClt.getWildcardAllowAssertions(tokenDomain);
        if (roleMap != null && !roleMap.isEmpty()) {
            if (actionByWildCardRole(action, tokenDomain, resource, roles, roleMap, matchRoleName)) {
                return AccessCheckStatus.ALLOW;
            } else {
                status = AccessCheckStatus.DENY_NO_MATCH;
            }
        } else if (status != AccessCheckStatus.DENY_NO_MATCH && roleMap != null) {
            status = AccessCheckStatus.DENY_DOMAIN_EMPTY;
        }

        if (status == AccessCheckStatus.DENY_DOMAIN_NOT_FOUND) {
            logger.warn("{}: No role map found for domain={} so access denied", msgPrefix, tokenDomain);
        } else if (status == AccessCheckStatus.DENY_DOMAIN_EMPTY) {
            logger.warn("{}: No policy assertions for domain={} so access denied", msgPrefix, tokenDomain);
        }

        return status;
    }

    private static boolean matchAssertions(List<Struct> asserts, String role, String action,
                                           String resource, StringBuilder matchRoleName,
                                           @Nullable String msgPrefix) {

        ZpeMatch matchStruct;
        String passertAction = null;
        String passertResource = null;
        String polName = null;

        for (Struct strAssert : asserts) {

            if (logger.isDebugEnabled()) {
                assert msgPrefix != null;
                // this strings are only used for debug statements so we'll
                // only retrieve them if debug option is enabled

                passertAction = strAssert.getString(ZpeConsts.ZPE_FIELD_ACTION);
                passertResource = strAssert.getString(ZpeConsts.ZPE_FIELD_RESOURCE);
                polName = strAssert.getString(ZpeConsts.ZPE_FIELD_POLICY_NAME);

                final String passertRole = strAssert.getString(ZpeConsts.ZPE_FIELD_ROLE);

                logger.debug(
                        "{}: Process Assertion: policy({}) assert-action={} assert-resource={} assert-role={}",
                        msgPrefix, polName, passertAction, passertResource, passertRole);
            }

            // ex: "mod*

            matchStruct = (ZpeMatch) strAssert.get(ZpeConsts.ZPE_ACTION_MATCH_STRUCT);
            if (!matchStruct.matches(action)) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "{}: policy({}) regexpr-match: FAILed: assert-action({}) doesn't match action({})",
                            msgPrefix, polName, passertAction, action);
                }
                continue;
            }

            // ex: "weather:service.storage.tenant.sports.*"
            matchStruct = (ZpeMatch) strAssert.get(ZpeConsts.ZPE_RESOURCE_MATCH_STRUCT);
            if (!matchStruct.matches(resource)) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "{}: policy({}) regexpr-match: FAILed: assert-resource({}) " +
                            "doesn't match resource({})", msgPrefix, polName, passertResource, resource);
                }
                continue;
            }

            // update the match role name

            matchRoleName.setLength(0);
            matchRoleName.append(role);

            return true;
        }

        return false;
    }

    private static boolean actionByRole(String action, String domain, String resource,
                                        List<String> roles, Map<String, List<Struct>> roleMap,
                                        StringBuilder matchRoleName) {

        // msgPrefix is only used in our debug statements so we're only
        // going to generate the value if debug is enabled

        String msgPrefix = null;
        if (logger.isDebugEnabled()) {
            msgPrefix = "allowActionByRole: domain(" + domain + ") action(" + action +
                        ") resource(" + resource + ')';
        }

        for (String role : roles) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: Process role ({})", msgPrefix, role);
            }

            final List<Struct> asserts = roleMap.get(role);
            if (asserts == null || asserts.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{}: No policy assertions in domain={} for role={} so access denied",
                                 msgPrefix, domain, role);
                }
                continue;
            }

            // see if any of its assertions match the action and resource
            // the assert action value does not have the domain prefix
            // ex: "Modify"
            // the assert resource value has the domain prefix
            // ex: "angler:angler.stuff"

            if (matchAssertions(asserts, role, action, resource, matchRoleName, msgPrefix)) {
                return true;
            }
        }

        return false;
    }

    private static boolean actionByWildCardRole(String action, String domain, String resource,
                                                List<String> roles, Map<String, List<Struct>> roleMap,
                                                StringBuilder matchRoleName) {

        String msgPrefix = null;
        if (logger.isDebugEnabled()) {
            msgPrefix = "allowActionByWildCardRole: domain(" + domain + ") action(" + action +
                        ") resource(" + resource + ')';
        }

        // find policy matching resource and action
        // get assertions for given domain+role
        // then cycle thru those assertions looking for matching action and resource

        // we will visit each of the wildcard roles
        //
        final Set<String> keys = roleMap.keySet();

        for (String role : roles) {

            if (logger.isDebugEnabled()) {
                logger.debug("{}: Process role ({})", msgPrefix, role);
            }

            for (String roleName : keys) {
                final List<Struct> asserts = roleMap.get(roleName);
                if (asserts == null || asserts.isEmpty()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: No policy assertions in domain={} for role={} so access denied",
                                     msgPrefix, domain, role);
                    }
                    continue;
                }

                final Struct structAssert = asserts.get(0);
                final ZpeMatch matchStruct = (ZpeMatch) structAssert.get(ZpeConsts.ZPE_ROLE_MATCH_STRUCT);
                if (!matchStruct.matches(role)) {
                    if (logger.isDebugEnabled()) {
                        final String polName = structAssert.getString(ZpeConsts.ZPE_FIELD_POLICY_NAME);
                        logger.debug(
                                "{}: policy({}) regexpr-match: FAILed: assert-role({}) doesnt match role({})",
                                msgPrefix, polName, roleName, role);
                    }
                    continue;
                }

                // HAVE: matched the role with the wildcard

                // see if any of its assertions match the action and resource
                // the assert action value does not have the domain prefix
                // ex: "Modify"
                // the assert resource value has the domain prefix
                // ex: "angler:angler.stuff"

                if (matchAssertions(asserts, roleName, action, resource, matchRoleName, msgPrefix)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static <T> void addTokenToCache(Map<String, T> tokenCache, final String tokenKey, T tokenValue) {
        tokenCache.put(tokenKey, tokenValue);
    }
}
