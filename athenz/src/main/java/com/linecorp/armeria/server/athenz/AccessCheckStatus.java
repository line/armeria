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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The status of an Athenz access check.
 */
@UnstableApi
public enum AccessCheckStatus {

    // Forked from https://github.com/AthenZ/athenz/blob/3acc0ea0e0f44adc0fb69bb442a0a449b655ad10/clients/java/zpe/src/main/java/com/yahoo/athenz/zpe/AuthZpeClient.java

    /**
     * Access was explicitly allowed.
     */
    ALLOW {
        @Override
        public String toString() {
            return "Access Check was explicitly allowed";
        }
    },
    /**
     * Access was explicitly denied.
     */
    DENY {
        @Override
        public String toString() {
            return "Access Check was explicitly denied";
        }
    },
    /**
     * Access was denied due to no match to any of the assertions defined in domain policy file.
     */
    DENY_NO_MATCH {
        @Override
        public String toString() {
            return "Access denied due to no match to any of the assertions defined in domain policy file";
        }
    },
    /**
     * Access was denied due to expired Role Token.
     */
    DENY_ROLETOKEN_EXPIRED {
        @Override
        public String toString() {
            return "Access denied due to expired Token";
        }
    },
    /**
     * Access was denied due to invalid Role Token.
     */
    DENY_ROLETOKEN_INVALID {
        @Override
        public String toString() {
            return "Access denied due to invalid Token";
        }
    },
    /**
     * Access was denied due to domain mismatch between Resource and Token.
     */
    DENY_DOMAIN_MISMATCH {
        @Override
        public String toString() {
            return "Access denied due to domain mismatch between Resource and Token";
        }
    },
    /**
     * Access was denied due to domain not found in library cache.
     */
    DENY_DOMAIN_NOT_FOUND {
        @Override
        public String toString() {
            return "Access denied due to domain not found in library cache";
        }
    },
    /**
     * Access was denied due to expired domain policy file.
     */
    DENY_DOMAIN_EXPIRED {
        @Override
        public String toString() {
            return "Access denied due to expired domain policy file";
        }
    },
    /**
     * Access was denied due to no policies in the domain file.
     */
    DENY_DOMAIN_EMPTY {
        @Override
        public String toString() {
            return "Access denied due to no policies in the domain file";
        }
    },
    /**
     * Access was denied due to invalid or empty action/resource values.
     */
    DENY_INVALID_PARAMETERS {
        @Override
        public String toString() {
            return "Access denied due to invalid/empty action/resource values";
        }
    },
    /**
     * Access was denied due to certificate mismatch in issuer.
     */
    DENY_CERT_MISMATCH_ISSUER {
        @Override
        public String toString() {
            return "Access denied due to certificate mismatch in issuer";
        }
    },
    /**
     * Access was denied due to missing subject in certificate.
     */
    DENY_CERT_MISSING_SUBJECT {
        @Override
        public String toString() {
            return "Access denied due to missing subject in certificate";
        }
    },
    /**
     * Access was denied due to missing domain name in certificate.
     */
    DENY_CERT_MISSING_DOMAIN {
        @Override
        public String toString() {
            return "Access denied due to missing domain name in certificate";
        }
    },
    /**
     * Access was denied due to missing role name in certificate.
     */
    DENY_CERT_MISSING_ROLE_NAME {
        @Override
        public String toString() {
            return "Access denied due to missing role name in certificate";
        }
    },
    /**
     * Access was denied due to access token certificate hash mismatch.
     */
    DENY_CERT_HASH_MISMATCH {
        @Override
        public String toString() {
            return "Access denied due to access token certificate hash mismatch";
        }
    }
}
