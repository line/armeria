/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

final class ThriftMetadataAccess {

    private static final Logger logger = LoggerFactory.getLogger(ThriftMetadataAccess.class);

    private static boolean preInitializeThriftClass;
    private static final Pattern preInitializeTargetPattern =
            Pattern.compile("^armeria-thrift0\\.(\\d+)\\..*$");

    static {
        try {
            final Enumeration<URL> versionPropertiesUrls =
                    ThriftMetadataAccess.class.getClassLoader().getResources(
                            "META-INF/com.linecorp.armeria.versions.properties");
            if (!versionPropertiesUrls.hasMoreElements()) {
                // versions.properties was not found
                logger.trace("Unable to determine the 'armeria-thrift' version. Please consider " +
                             "adding 'META-INF/com.linecorp.armeria.versions.properties' to the " +
                             "classpath to avoid unexpected issues.");
            }
            boolean preInitializeThriftClass = false;
            while (versionPropertiesUrls.hasMoreElements()) {
                final URL url = versionPropertiesUrls.nextElement();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    final Properties props = new Properties();
                    props.load(reader);
                    preInitializeThriftClass = needsPreInitialization(props);
                    if (preInitializeThriftClass) {
                        break;
                    }
                }
            }
            ThriftMetadataAccess.preInitializeThriftClass = preInitializeThriftClass;
        } catch (Exception e) {
            logger.debug("Unexpected exception while determining the 'armeria-thrift' version: ", e);
        }
    }

    @VisibleForTesting
    static boolean needsPreInitialization(Properties props) {
        for (String key : props.stringPropertyNames()) {
            final Matcher matcher = preInitializeTargetPattern.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            final int version = Integer.parseInt(matcher.group(1));
            if (version <= 14) {
                logger.trace("Pre-initializing thrift metadata due to 'armeria-thrift0.{}'", version);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static <T extends TBase<T, F>, F extends TFieldIdEnum>
    Map<?, FieldMetaData> getStructMetaDataMap(Class<?> clazz) {
        // Pre-initialize classes if there is a jar in the classpath with armeria-thrift <= 0.14
        // See the following issue for the motivation of pre-initializing classes
        // https://issues.apache.org/jira/browse/THRIFT-5430
        if (preInitializeThriftClass) {
            try {
                Class.forName(clazz.getName(), true, clazz.getClassLoader());
            } catch (ClassNotFoundException e) {
                // Another exception will probably be raised in the actual getStructMetaDataMap so just
                // logging for at this point.
                logger.trace("Unexpected exception while initializing class {}: ", clazz, e);
            }
        }
        return FieldMetaData.getStructMetaDataMap((Class<T>) clazz);
    }

    private ThriftMetadataAccess() {}
}
