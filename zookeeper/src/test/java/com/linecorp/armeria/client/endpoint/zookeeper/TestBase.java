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
package com.linecorp.armeria.client.endpoint.zookeeper;

import static org.junit.Assert.fail;

import java.io.File;
import java.time.Duration;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.zookeeper.common.Codec;
import com.linecorp.armeria.client.endpoint.zookeeper.common.DefaultCodec;

import zookeeperjunit.ZKFactory;
import zookeeperjunit.ZKInstance;

public class TestBase {
    protected static final Logger logger = LoggerFactory.getLogger(EndpointGroupTest.class);
    protected static final File ROOT_DIR = new File("build" + File.separator + "zookeeper");
    protected static final ZKInstance zkInstance = ZKFactory.apply().withRootDir(ROOT_DIR).create();
    protected static final String zNode = "/testEndPoints";
    protected static final int sessionTimeout = 300000;
    protected static final Codec codec = new DefaultCodec();
    protected static final Set<Endpoint> sampleEndpoints = ImmutableSet.of(Endpoint.of("127.0.0.1", 1234, 2),
                                                                           Endpoint.of("127.0.0.1", 2345, 4),
                                                                           Endpoint.of("127.0.0.1", 3456, 2));
    private static final Duration duration = Duration.ofSeconds(5);


    @BeforeClass
    public static void start() {
        try {
            zkInstance.start().result(duration);
        } catch (Throwable throwable) {
            fail();
        }
    }

    @AfterClass
    public static void stop() {
        try {
            zkInstance.stop().ready(duration);
        } catch (Exception e) {
            logger.warn("Failed to stop the ZooKeeperProxy instance", e);
        }
    }

    public ZKInstance instance() {
        return zkInstance;
    }
}
