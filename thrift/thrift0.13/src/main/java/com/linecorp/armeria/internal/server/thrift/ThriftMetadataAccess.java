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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
            Pattern.compile("^armeria-thrift0\\.(\\d+)$");

    private static final String filename =
            "com/linecorp/armeria/internal/common/thrift-options.properties";

    static {
        try {
            final Enumeration<URL> versionPropertiesUrls =
                    ThriftMetadataAccess.class.getClassLoader().getResources(filename);
            final List<Properties> propertiesList = new ArrayList<>();
            while (versionPropertiesUrls.hasMoreElements()) {
                final URL url = versionPropertiesUrls.nextElement();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()))) {
                    final Properties props = new Properties();
                    props.load(reader);
                    propertiesList.add(props);
                }
            }
            preInitializeThriftClass = needsPreInitialization(propertiesList);
        } catch (Exception e) {
            logger.debug("Unexpected exception while extracting options: ", e);
        }
    }

    @VisibleForTesting
    static boolean needsPreInitialization(List<Properties> propertiesList) {
        if (propertiesList.isEmpty()) {
            // versions.properties was not found
            logger.debug("Unable to find a '{}' file. You may want to consider " +
                         "checking if the file has been omitted from the classpath to avoid " +
                         "unexpected issues.", filename);
            return true;
        }
        if (propertiesList.size() > 1) {
            logger.debug("More than one '{}' file has been found. You may want to consider " +
                         "checking if more than one 'armeria-thrift' module has been added " +
                         "to the classpath", filename);
            return true;
        }
        final Properties properties = propertiesList.get(0);
        return "true".equals(properties.getProperty("structPreinitRequired"));
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
