/*
 * Copyright 2020 LINE Corporation
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
/**
 * A package to support the compatibility with
 * <a href="https://cloud.spring.io/spring-cloud-zookeeper/reference/html/">Spring Cloud Zookeeper</a>.
 * Spring Cloud Zookeeper uses
 * <a href="https://github.com/apache/curator/blob/apache-curator-5.0.0/curator-x-discovery/src/main/java/org/apache/curator/x/discovery/ServiceInstance.java">
 * ServiceInstance</a> with the payload {@link org.springframework.cloud.zookeeper.discovery.ZookeeperInstance}.
 */
@NonNullByDefault
package org.springframework.cloud.zookeeper.discovery;

import com.linecorp.armeria.common.util.NonNullByDefault;
