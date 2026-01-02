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

import static com.linecorp.armeria.server.athenz.MinifiedAuthZpeClient.stripDomainPrefix;

import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.athenz.auth.util.Crypto;
import com.yahoo.athenz.common.utils.SignUtils;
import com.yahoo.athenz.zpe.ZpeConsts;
import com.yahoo.athenz.zpe.match.ZpeMatch;
import com.yahoo.athenz.zpe.match.impl.ZpeMatchAll;
import com.yahoo.athenz.zpe.match.impl.ZpeMatchEqual;
import com.yahoo.athenz.zpe.match.impl.ZpeMatchRegex;
import com.yahoo.athenz.zpe.match.impl.ZpeMatchStartsWith;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.athenz.zts.Assertion;
import com.yahoo.athenz.zts.AssertionEffect;
import com.yahoo.athenz.zts.DomainSignedPolicyData;
import com.yahoo.athenz.zts.JWSPolicyData;
import com.yahoo.athenz.zts.Policy;
import com.yahoo.athenz.zts.PolicyData;
import com.yahoo.athenz.zts.SignedPolicyData;
import com.yahoo.rdl.Struct;

final class AthenzPolicyHandler {

    // Forked from: https://github.com/AthenZ/athenz/blob/7e326fa655fef997ce913267f9dd561a9f4c82dd/clients/java/zpe/src/main/java/com/yahoo/athenz/zpe/ZpeUpdPolLoader.java#L333
    // Modified to use PublicKeyStore to fetch ZTS/ZMS keys instead of reading from file system.

    private static final Logger logger = LoggerFactory.getLogger(AthenzPolicyHandler.class);

    private static final boolean checkPolicyZMSSignature = Boolean.parseBoolean(
            System.getProperty(ZpeConsts.ZPE_PROP_CHECK_POLICY_ZMS_SIGNATURE, "false"));

    private final PublicKeyStore publicKeyStore;
    private final ObjectMapper mapper;

    AthenzPolicyHandler(PublicKeyStore publicKeyStore) {
        this.publicKeyStore = publicKeyStore;
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static ZpeMatch getMatchObject(String value) {
        final ZpeMatch match;
        if ("*".equals(value)) {
            match = new ZpeMatchAll();
        } else {
            final int anyCharMatch = value.indexOf('*');
            final int singleCharMatch = value.indexOf('?');

            if (anyCharMatch == -1 && singleCharMatch == -1) {
                match = new ZpeMatchEqual(value);
            } else if (anyCharMatch == value.length() - 1 && singleCharMatch == -1) {
                match = new ZpeMatchStartsWith(value.substring(0, value.length() - 1));
            } else {
                match = new ZpeMatchRegex(value);
            }
        }

        return match;
    }

    PolicyData getJWSPolicyData(JWSPolicyData jwsPolicyData) {

        // first we're going to assume that our signature was provided in P1363 format
        // since that's what zpu is asking for by default.

        final String derSignature = getDERSignature(jwsPolicyData.getProtectedHeader(),
                                                    jwsPolicyData.getSignature());
        if (derSignature == null || !Crypto.validateJWSDocument(jwsPolicyData.getProtectedHeader(),
                                                                jwsPolicyData.getPayload(),
                                                                derSignature, publicKeyStore::getZtsKey)) {

            // assume the signature was already in DER format, so we'll use it directly

            if (!Crypto.validateJWSDocument(jwsPolicyData.getProtectedHeader(), jwsPolicyData.getPayload(),
                                            jwsPolicyData.getSignature(), publicKeyStore::getZtsKey)) {
                throw new AthenzPolicyException("ZTS signature validation failed");
            }
        }

        final Base64.Decoder base64Decoder = Base64.getUrlDecoder();
        final byte[] payload = base64Decoder.decode(jwsPolicyData.getPayload());
        try {
            final SignedPolicyData signedPolicyData = mapper.readValue(payload, SignedPolicyData.class);
            return signedPolicyData.getPolicyData();
        } catch (IOException e) {
            throw new AthenzPolicyException("Unable to parse jws policy data payload, ", e);
        }
    }

    private static boolean isESAlgorithm(@Nullable String algorithm) {
        if (algorithm != null) {
            switch (algorithm) {
                case "ES256":
                case "ES384":
                case "ES512":
                    return true;
            }
        }
        return false;
    }

    @Nullable
    private static String getDERSignature(final String protectedHeader, final String signature) {

        final Map<String, String> header = Crypto.parseJWSProtectedHeader(protectedHeader);
        if (header == null) {
            return null;
        }
        final String algorithm = header.get("alg");
        if (!isESAlgorithm(algorithm)) {
            return null;
        }
        try {
            final Base64.Decoder base64Decoder = Base64.getUrlDecoder();
            final byte[] signatureBytes = base64Decoder.decode(signature);
            final byte[] convertedSignature = Crypto.convertSignatureFromP1363ToDERFormat(
                    signatureBytes, Crypto.getDigestAlgorithm(algorithm));
            final Base64.Encoder base64Encoder = Base64.getUrlEncoder().withoutPadding();
            return base64Encoder.encodeToString(convertedSignature);
        } catch (Exception ex) {
            return null;
        }
    }

    PolicyData getSignedPolicyData(DomainSignedPolicyData domainSignedPolicyData) {
        // we already verified that the object has policy data present

        final SignedPolicyData signedPolicyData = domainSignedPolicyData.getSignedPolicyData();

        final String ztsSignature = domainSignedPolicyData.getSignature();
        final String ztsKeyId = domainSignedPolicyData.getKeyId();

        // first let's verify the ZTS signature for our policy file

        final PublicKey ztsPublicKey = publicKeyStore.getZtsKey(ztsKeyId);
        if (ztsPublicKey == null) {
            throw new AthenzPolicyException("Unable to fetch zts public key for id: " + ztsKeyId);
        }

        if (!Crypto.verify(SignUtils.asCanonicalString(signedPolicyData), ztsPublicKey, ztsSignature)) {
            throw new AthenzPolicyException("ZTS signature validation failed");
        }

        final PolicyData policyData = signedPolicyData.getPolicyData();
        if (policyData == null) {
            throw new AthenzPolicyException("Missing policy data");
        }

        // now let's verify that the ZMS signature for our policy file
        // by default we're skipping this check because with multi-policy
        // support we'll be returning different versions of the policy
        // data from ZTS which cannot be signed by ZMS

        if (checkPolicyZMSSignature) {

            final String zmsSignature = signedPolicyData.getZmsSignature();
            final String zmsKeyId = signedPolicyData.getZmsKeyId();

            final PublicKey zmsPublicKey = publicKeyStore.getZmsKey(zmsKeyId);
            if (zmsPublicKey == null) {
                throw new AthenzPolicyException("unable to fetch zms public key for id: " + zmsKeyId);
            }

            if (!Crypto.verify(SignUtils.asCanonicalString(policyData), zmsPublicKey, zmsSignature)) {
                throw new AthenzPolicyException("ZMS signature validation failed");
            }
        }
        return policyData;
    }

    /**
     * Process the policies into assertions, process the assertions: action, resource, role.
     * If there is a wildcard in the action or resource, compile the regexpr and place it into the assertion
     * Struct. This is a performance enhancement for AuthZpeClient when it performs the authorization checks.
     */
    static AthenzAssertions toAssertions(PolicyData policyData) {
        final AthenzAssertions athenzAssertions = new AthenzAssertions();
        final String domainName = policyData.getDomain();
        final List<Policy> policies = policyData.getPolicies();
        for (Policy policy : policies) {
            final String pname = policy.getName();
            logger.debug("Process policy {}. domain({}) ", pname, domainName);
            final List<Assertion> assertions = policy.getAssertions();
            if (assertions == null) {
                continue;
            }
            for (Assertion assertion : assertions) {
                final Struct strAssert = new Struct();
                strAssert.put(ZpeConsts.ZPE_FIELD_POLICY_NAME, pname);

                // It is possible for action and resource to retain case. Need to lower them both.
                final String passertAction = assertion.getAction().toLowerCase();

                ZpeMatch matchStruct = getMatchObject(passertAction);
                strAssert.put(ZpeConsts.ZPE_ACTION_MATCH_STRUCT, matchStruct);

                final String passertResource = assertion.getResource().toLowerCase();
                final String rsrc = stripDomainPrefix(passertResource, domainName, passertResource);
                assert rsrc != null;
                strAssert.put(ZpeConsts.ZPE_FIELD_RESOURCE, rsrc);
                matchStruct = getMatchObject(rsrc);
                strAssert.put(ZpeConsts.ZPE_RESOURCE_MATCH_STRUCT, matchStruct);

                final String passertRole = assertion.getRole();
                String pRoleName = stripDomainPrefix(passertRole, domainName, passertRole);
                assert pRoleName != null;
                // strip the prefix "role." too
                pRoleName = pRoleName.replaceFirst("^role.", "");
                strAssert.put(ZpeConsts.ZPE_FIELD_ROLE, pRoleName);

                // based on the effect and role name determine what
                // map we're going to use

                final Map<String, List<Struct>> roleMap;
                final AssertionEffect passertEffect = assertion.getEffect();
                matchStruct = getMatchObject(pRoleName);
                strAssert.put(ZpeConsts.ZPE_ROLE_MATCH_STRUCT, matchStruct);

                if (passertEffect != null && passertEffect.toString().compareTo("DENY") == 0) {
                    if (matchStruct instanceof ZpeMatchEqual) {
                        roleMap = athenzAssertions.roleStandardDenyMap();
                    } else {
                        roleMap = athenzAssertions.roleWildcardDenyMap();
                    }
                } else {
                    if (matchStruct instanceof ZpeMatchEqual) {
                        roleMap = athenzAssertions.roleStandardAllowMap();
                    } else {
                        roleMap = athenzAssertions.roleWildcardAllowMap();
                    }
                }

                final List<Struct> assertList = roleMap.computeIfAbsent(pRoleName, k -> new ArrayList<>());
                assertList.add(strAssert);
            }
        }
        return athenzAssertions;
    }
}
