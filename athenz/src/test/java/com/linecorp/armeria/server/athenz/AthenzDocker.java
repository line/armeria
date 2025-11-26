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

package com.linecorp.armeria.server.athenz;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.google.common.collect.ImmutableList;
import com.oath.auth.Utils;
import com.yahoo.athenz.auth.util.Crypto;
import com.yahoo.athenz.zms.Assertion;
import com.yahoo.athenz.zms.AssertionEffect;
import com.yahoo.athenz.zms.Policy;
import com.yahoo.athenz.zms.PublicKeyEntry;
import com.yahoo.athenz.zms.Role;
import com.yahoo.athenz.zms.ServiceIdentity;
import com.yahoo.athenz.zms.TopLevelDomain;
import com.yahoo.athenz.zms.ZMSClient;
import com.yahoo.athenz.zts.ZTSClient;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.athenz.ZtsBaseClientBuilder;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.micrometer.core.instrument.util.IOUtils;

public class AthenzDocker implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AthenzDocker.class);

    public static final String ZMS_SERVICE_NAME = "zms-server";
    public static final String ZTS_SERVICE_NAME = "zts-server";
    public static final int ZMS_PORT = 4443;
    public static final int ZTS_PORT = 8443;

    public static final String ATHENZ_CERTS = "/docker/certs/";
    public static final String CA_CERT_FILE = ATHENZ_CERTS + "CAs/athenz_ca_cert.pem";
    public static final String TEST_DOMAIN_NAME = "testing";
    public static final String TEST_SERVICE = "test-service";
    public static final String FOO_SERVICE = "foo-service";

    public static final String ADMIN_ROLE = "test_role_admin";
    public static final String USER_ROLE = "test_role_users";
    public static final String ADMIN_POLICY = "admin-policy";
    public static final String USER_POLICY = "user-policy";

    private final ComposeContainer composeContainer;

    @Nullable
    private URI ztsUri;
    @Nullable
    private ZMSClient zmsClient;
    private boolean initialized;

    public AthenzDocker(File dockerComposeFile) {
        composeContainer =
                new ComposeContainer(dockerComposeFile)
                        .withLocalCompose(true)
                        .withExposedService(ZMS_SERVICE_NAME, ZMS_PORT, Wait.forHealthcheck())
                        .withExposedService(ZTS_SERVICE_NAME, ZTS_PORT, Wait.forHealthcheck());
    }

    private ZMSClient zmsClient() {
        if (zmsClient == null) {
            zmsClient = newZmsClient();
        }
        return zmsClient;
    }

    public boolean initialize() {
        if (initialized) {
            return true;
        }

        final int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                logger.info("Initializing Athenz Docker container (attempt {}/{})...", attempt, maxAttempts);
                composeContainer.start();
                defaultScaffold();
                initialized = true;
                return true;
            } catch (Exception e) {
                logger.warn("Attempt {}/{} to initialize Athenz Docker container failed.",
                            attempt, maxAttempts, e);
            }
        }
        return false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Override this method to create your own test domain, services, roles, and policies.
     */
    protected void scaffold(ZMSClient zmsClient) {}

    @Override
    public void close() {
        composeContainer.stop();
    }

    private void defaultScaffold() {
        logger.info("Creating default Athenz domain, services, roles, and policies...");
        try {
            // Delete test domain if it exists
            deleteDomain(TEST_DOMAIN_NAME);
        } catch (Exception e) {
            // Ignore if the domain does not exist
        }

        // Create test domain
        createDomain(TEST_DOMAIN_NAME);
        // Create test service
        final String testServicePublicKeyId = createService(TEST_SERVICE);
        final String fooServicePublicKeyId = createService(FOO_SERVICE);

        createRole(USER_ROLE, ImmutableList.of(TEST_DOMAIN_NAME + '.' + TEST_SERVICE,
                                               TEST_DOMAIN_NAME + '.' + FOO_SERVICE));
        // Admin role is only granted to the test service
        createRole(ADMIN_ROLE, ImmutableList.of(TEST_DOMAIN_NAME + '.' + TEST_SERVICE));

        createPolicy(USER_POLICY, USER_ROLE, "files", "obtain");
        createPolicy(ADMIN_POLICY, ADMIN_ROLE, "secrets", "obtain");

        // Create user-defined roles and policies.
        scaffold(zmsClient());

        try (ZTSClient ztsAdminClient = newZtsClient("domain-admin")) {
            // Wait for ZTS to sync.
            final int maxIterations = 20;
            for (int i = 1; i <= maxIterations; i++) {
                try {
                    final com.yahoo.athenz.zts.ServiceIdentity testServiceIdentity =
                            ztsAdminClient.getServiceIdentity(TEST_DOMAIN_NAME, TEST_SERVICE);
                    boolean found = testServiceIdentity.getPublicKeys()
                                                       .stream()
                                                       .anyMatch(key -> {
                                                           return key.getId().equals(testServicePublicKeyId);
                                                       });
                    if (!found) {
                        continue;
                    }
                    final com.yahoo.athenz.zts.ServiceIdentity fooServiceIdentity =
                            ztsAdminClient.getServiceIdentity(TEST_DOMAIN_NAME, FOO_SERVICE);
                    found = fooServiceIdentity.getPublicKeys()
                                              .stream()
                                              .anyMatch(key -> {
                                                  return key.getId().equals(fooServicePublicKeyId);
                                              });
                    if (found) {
                        // We have the service identities, so we can stop waiting.
                        break;
                    }
                } catch (Exception e) {
                    if (i == maxIterations) {
                        throw new IllegalStateException("Failed to find test service identities", e);
                    } else {
                        // Swallow the exception and retry
                    }
                }

                logger.debug("Test service identities not found yet, retrying... (attempts: {})", i);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public void createPolicy(String policyName, String roleName, String resourceName, String action) {
        final String assertionRole = TEST_DOMAIN_NAME + ":role." + roleName;
        final String assertionResource = TEST_DOMAIN_NAME + ':' + resourceName;
        final Assertion assertion = new Assertion();
        assertion.setRole(assertionRole);
        assertion.setAction(action);
        assertion.setResource(assertionResource);
        assertion.setEffect(AssertionEffect.ALLOW);

        final Policy policyToCreate = new Policy();
        policyToCreate.setName(TEST_DOMAIN_NAME + ":policy." + policyName);
        policyToCreate.setAssertions(ImmutableList.of(assertion));

        zmsClient().putPolicy(TEST_DOMAIN_NAME, policyName, "create-policy-audit-ref", policyToCreate);
    }

    public void createRole(String roleName, List<String> members) {
        final Role role = new Role().setName(TEST_DOMAIN_NAME + ":role." + roleName)
                                    .setMembers(members);
        zmsClient().putRole(TEST_DOMAIN_NAME, roleName, "create-role-audit-ref", role);
    }

    public String createService(String serviceName) {
        final String publicCert = readFile(ATHENZ_CERTS + serviceName + "/public.pem");
        final String ybase64PublicKey = Crypto.ybase64EncodeString(publicCert);
        final String publicKeyId = serviceName + "_public_key";

        final PublicKeyEntry publicKeyEntry = new PublicKeyEntry();
        publicKeyEntry.setId(publicKeyId);
        publicKeyEntry.setKey(ybase64PublicKey);
        final ServiceIdentity serviceIdentity = new ServiceIdentity();
        serviceIdentity.setName(TEST_DOMAIN_NAME + '.' + serviceName);
        serviceIdentity.setPublicKeys(Collections.singletonList(publicKeyEntry));

        zmsClient().putServiceIdentity(TEST_DOMAIN_NAME, serviceName, "create-service-audit-ref",
                                       serviceIdentity);
        return publicKeyId;
    }

    public void deleteDomain(String domainName) {
        zmsClient().deleteTopLevelDomain(domainName, "delete-domain-audit-ref");
    }

    public void createDomain(String domainName) {
        final TopLevelDomain domain = new TopLevelDomain();
        domain.setName(domainName);
        domain.setDescription("A test domain created by the Java client.");
        domain.setAdminUsers(ImmutableList.of("user.github-7654321"));
        zmsClient().postTopLevelDomain("create-domain-audit-ref", domain);
    }

    public URI ztsUri() {
        if (ztsUri == null) {
            final String serviceHost = composeContainer.getServiceHost(ZTS_SERVICE_NAME, ZTS_PORT);
            final int servicePort = composeContainer.getServicePort(ZTS_SERVICE_NAME, ZTS_PORT);
            ztsUri = URI.create("https://" + serviceHost + ':' + servicePort);
        }
        return ztsUri;
    }

    public ZtsBaseClient newZtsBaseClient(String serviceName) {
        return newZtsBaseClient(serviceName, clientBuilder -> {
        });
    }

    public ZtsBaseClient newZtsBaseClient(String serviceName,
                                          Consumer<ZtsBaseClientBuilder> clientConfigurer) {
        final String serviceKeyFile = ATHENZ_CERTS + serviceName + "/key.pem";
        final String serviceCertFile = ATHENZ_CERTS + serviceName + "/cert.pem";
        try (InputStream serviceKey = AthenzDocker.class.getResourceAsStream(serviceKeyFile);
             InputStream serviceCert = AthenzDocker.class.getResourceAsStream(serviceCertFile);
             InputStream caCert = AthenzDocker.class.getResourceAsStream(CA_CERT_FILE)) {
            final TlsKeyPair tlsKeyPair = TlsKeyPair.of(serviceKey, serviceCert);
            final ZtsBaseClientBuilder ztsClientBuilder =
                    ZtsBaseClient.builder(ztsUri())
                                 .keyPair(() -> tlsKeyPair)
                                 .trustedCertificate(caCert);
            clientConfigurer.accept(ztsClientBuilder);
            return ztsClientBuilder.build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ZMSClient newZmsClient() {
        final String serviceHost = composeContainer.getServiceHost(ZMS_SERVICE_NAME, ZMS_PORT);
        final int servicePort = composeContainer.getServicePort(ZMS_SERVICE_NAME, ZMS_PORT);
        final String zmsUrl = "https://" + serviceHost + ':' + servicePort;
        return new ZMSClient(zmsUrl, getSslContext("domain-admin"));
    }

    private ZTSClient newZtsClient(String serviceName) {
        final String serviceHost = composeContainer.getServiceHost(ZTS_SERVICE_NAME, ZTS_PORT);
        final Integer servicePort = composeContainer.getServicePort(ZTS_SERVICE_NAME, ZTS_PORT);
        final String ztsUrl = "https://" + serviceHost + ':' + servicePort;
        return new ZTSClient(ztsUrl, getSslContext(serviceName));
    }

    private static SSLContext getSslContext(String serviceName) {
        final String domainAdminCertFile = ATHENZ_CERTS + serviceName + "/cert.pem";
        final String domainAdminKeyFile = ATHENZ_CERTS + serviceName + "/key.pem";
        return getSSLContext(CA_CERT_FILE, domainAdminKeyFile, domainAdminCertFile);
    }

    private static SSLContext getSSLContext(String caCertFile,
                                            String athenzPrivateKeyFile, String athenzPublicCertFile) {
        final String caCert = readFile(caCertFile);
        final String athenzPublicCert = readFile(athenzPublicCertFile);
        final String athenzPrivateKey = readFile(athenzPrivateKeyFile);
        try {
            return Utils.buildSSLContext(caCert, athenzPublicCert, athenzPrivateKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readFile(String fileName) {
        try (InputStream is = AthenzDocker.class.getResourceAsStream(fileName)) {
            return IOUtils.toString(is);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read file: " + fileName, e);
        }
    }
}
