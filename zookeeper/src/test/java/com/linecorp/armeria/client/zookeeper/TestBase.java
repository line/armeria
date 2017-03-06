/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.zookeeper;

import java.time.Duration;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;

import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;

public class TestBase {
    protected static final Logger logger = LoggerFactory.getLogger(EndpointGroupTest.class);
    protected static final String zNode = "/testEndPoints";
    protected static final int sessionTimeout = 20000;
    protected static final Set<Endpoint> sampleEndpoints = ImmutableSet.of(Endpoint.of("127.0.0.1", 1234, 2),
                                                                           Endpoint.of("127.0.0.1", 2345, 4),
                                                                           Endpoint.of("127.0.0.1", 3456, 2));
    private static final Duration duration = Duration.ofSeconds(10);
    @ClassRule
    public static final TemporaryFolder ROOT_FOLDER = new TemporaryFolder();
    private static ZKInstance zkInstance;

    @BeforeClass
    public static void start() {
        try {
            zkInstance = ZKFactory.apply().withRootDir(ROOT_FOLDER.newFolder("zookeeper")).create();
            zkInstance.start().result(duration);
        } catch (Throwable throwable) {
            throw new IllegalStateException(throwable);
        }
    }

    @AfterClass
    public static void stop() {
        try {
            zkInstance.stop().ready(duration);
        } catch (Exception e) {
            logger.warn("Failed to stop the ZooKeeper zkInstance", e);
        }
    }

    public ZKInstance instance() {
        return zkInstance;
    }
}
