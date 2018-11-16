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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.security.KeyStore;

import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class KeyStoreCredentialResolverBuilderTest {

    @ClassRule
    public static TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void expectSuccessWithFile() throws Exception {
        final File file = folder.newFile();

        assertThat(file.length()).isZero();

        final KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.store(new FileOutputStream(file), "".toCharArray());

        assertThat(file.length()).isGreaterThan(0);
        assertThat(file.canRead()).isTrue();
        assertThat(file.exists()).isTrue();

        new KeyStoreCredentialResolverBuilder(file).build();
    }

    @Test
    public void expectSuccessWithResource() throws Exception {
        new KeyStoreCredentialResolverBuilder("keystore/test.jks").build();
    }

    @Test
    public void expectNotFound() throws Exception {
        assertThatThrownBy(
                () -> new KeyStoreCredentialResolverBuilder(new File("/not_exist")).build())
                .isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(
                () -> new KeyStoreCredentialResolverBuilder("not_exist").build())
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("Resource not found");
    }
}
