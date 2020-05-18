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
/*
 * Copyright 2015-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.zookeeper.discovery;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the default payload of a registered service in Zookeeper.
 *
 * @author Spencer Gibb
 * @since 1.0.0
 */
public class ZookeeperInstance {

    // Forked from https://github.com/spring-cloud/spring-cloud-zookeeper/blob/v3.0.0.M1/spring-cloud-zookeeper-discovery/src/main/java/org/springframework/cloud/zookeeper/discovery/ZookeeperInstance.java

    private String id;

    private String name;

    private Map<String, String> metadata = new HashMap<>();

    @SuppressWarnings("unused")
    private ZookeeperInstance() {
    }

    /**
     * Creates a new instance.
     */
    public ZookeeperInstance(String id, String name, Map<String, String> metadata) {
        // requireNonNull is not used.
        this.id = id;
        this.name = name;
        this.metadata = metadata;
    }

    /**
     * Returns the ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the name.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the ID.
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Sets the name.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the metadata.
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Sets the metadata.
     */
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZookeeperInstance)) {
            return false;
        }
        final ZookeeperInstance that = (ZookeeperInstance) o;
        return id.equals(that.id) &&
               name.equals(that.name) &&
               metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return (id.hashCode() * 31) + name.hashCode() * 31 + metadata.hashCode();
    }

    @Override
    public String toString() {
        return "ZookeeperInstance{" + "id='" + id + '\'' + ", name='" + name +
               '\'' + ", metadata=" + metadata + '}';
    }
}
