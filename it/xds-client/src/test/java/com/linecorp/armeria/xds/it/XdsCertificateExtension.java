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
package com.linecorp.armeria.xds.it;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import org.junit.jupiter.api.extension.ExtensionContext;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.testing.junit5.common.AbstractAllOrEachExtension;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;

/**
 * Wraps a single {@link SelfSignedCertificateExtension}, manages its lifecycle, and copies
 * certificate and private key files into an isolated temporary directory.
 *
 * <p>On macOS the JDK uses {@code PollingWatchService} which stats every file in the watched
 * directory during registration. Since {@link SelfSignedCertificateExtension} creates files
 * directly in {@code $TMPDIR}, watching that directory is racy — unrelated temp files may be
 * deleted concurrently, causing {@code NoSuchFileException}. This extension avoids the problem
 * by transparently returning file references that point to a dedicated directory.
 *
 * <p>This extension initializes the given {@link SelfSignedCertificateExtension} itself,
 * so it should <b>not</b> be separately registered with {@code @RegisterExtension}.
 *
 * <p>Usage:
 * <pre>{@code
 * @RegisterExtension
 * static final XdsCertificateExtension cert =
 *         new XdsCertificateExtension(new SelfSignedCertificateExtension("localhost"));
 * }</pre>
 */
public final class XdsCertificateExtension extends AbstractAllOrEachExtension {

    private final SelfSignedCertificateExtension delegate;
    private Path tempDir;
    private File certificateFile;
    private File privateKeyFile;

    public XdsCertificateExtension(SelfSignedCertificateExtension delegate) {
        this.delegate = delegate;
    }

    @Override
    protected void before(ExtensionContext context) throws Exception {
        delegate.before(context);
        tempDir = Files.createTempDirectory("xds-certs-");
        certificateFile = copyFile(delegate.certificateFile());
        privateKeyFile = copyFile(delegate.privateKeyFile());
    }

    @Override
    protected void after(ExtensionContext context) throws Exception {
        delegate.after(context);
        if (tempDir != null) {
            MoreFiles.deleteRecursively(tempDir, RecursiveDeleteOption.ALLOW_INSECURE);
        }
    }

    public File certificateFile() {
        return certificateFile;
    }

    public File privateKeyFile() {
        return privateKeyFile;
    }

    public X509Certificate certificate() {
        return delegate.certificate();
    }

    public PrivateKey privateKey() {
        return delegate.privateKey();
    }

    public TlsKeyPair tlsKeyPair() {
        return delegate.tlsKeyPair();
    }

    private File copyFile(File file) {
        try {
            final Path target = tempDir.resolve(file.getName());
            Files.copy(file.toPath(), target);
            return target.toFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
