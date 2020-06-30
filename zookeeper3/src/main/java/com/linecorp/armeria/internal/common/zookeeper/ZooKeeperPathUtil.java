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
package com.linecorp.armeria.internal.common.zookeeper;

import static java.util.Objects.requireNonNull;

import org.apache.zookeeper.common.PathUtils;

/**
 * A utility class for ZooKeeper path.
 */
public final class ZooKeeperPathUtil {

    /**
     * Validates a ZooKeeper path.
     */
    public static String validatePath(String path, String name) {
        requireNonNull(path, name);
        if (path.indexOf('/') > 0) {
            throw new IllegalArgumentException(name + " cannot have '/'. " + name + ": " + path);
        }
        try {
            // Simply prepend '/' to validate the path.
            PathUtils.validatePath('/' + path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": " + path + " (reason: " + e.getMessage() + ')');
        }
        return path;
    }

    private ZooKeeperPathUtil() {}
}
