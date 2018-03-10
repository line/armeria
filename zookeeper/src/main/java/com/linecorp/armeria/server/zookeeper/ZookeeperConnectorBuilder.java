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

package com.linecorp.armeria.server.zookeeper;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.nodes.GroupMember;
import org.apache.curator.framework.recipes.nodes.PersistentNode;
import org.apache.curator.framework.recipes.nodes.PersistentTtlNode;
import org.apache.curator.framework.recipes.queue.DistributedDelayQueue;
import org.apache.curator.framework.recipes.queue.DistributedIdQueue;
import org.apache.curator.framework.recipes.queue.DistributedPriorityQueue;
import org.apache.curator.framework.recipes.queue.DistributedQueue;
import org.apache.curator.framework.recipes.shared.SharedCount;

import com.google.common.collect.Lists;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListener;
import com.linecorp.armeria.server.ServerListenerBuilder;

/**
 * Provides armeria-zookeeper connectivity for {@link CuratorFramework} and its high-level abstractions, like
 * following:
 *
 * <p>- Starts given {@link CuratorFramework} instances in specified order when {@link Server} started.
 * - Stops given {@link CuratorFramework} instances in reverse order when {@link Server} stopping.
 *
 * <p>Note: you don't have to start or close the {@link CuratorFramework} instance used in
 * {@link ZooKeeperUpdatingListenerBuilder}.
 * */
public class ZookeeperConnectorBuilder {
    final List<Consumer<? super Server>> starters = new ArrayList<>();
    final List<Consumer<? super Server>> stoppers = new ArrayList<>();

    /**
     * Add connectivity of {@link CuratorFramework} client.
     * */
    public ZookeeperConnectorBuilder curatorFramework(CuratorFramework curatorFramework) {
        requireNonNull(curatorFramework);
        starters.add((unused) -> curatorFramework.start());
        stoppers.add((unused) -> curatorFramework.close());
        return this;
    }

    /**
     * Add connectivity of {@link LeaderLatch} instance.
     * */
    public ZookeeperConnectorBuilder leaderLatch(LeaderLatch leaderLatch) {
        requireNonNull(leaderLatch);
        starters.add((unused) -> {
            try {
                leaderLatch.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                leaderLatch.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link LeaderSelector} instance.
     * */
    public ZookeeperConnectorBuilder leaderSelector(LeaderSelector leaderSelector) {
        requireNonNull(leaderSelector);
        starters.add((unused) -> leaderSelector.start());
        stoppers.add((unused) -> leaderSelector.close());
        return this;
    }

    /**
     * Add connectivity of {@link SharedCount} instance.
     * */
    public ZookeeperConnectorBuilder sharedCount(SharedCount sharedCount) {
        requireNonNull(sharedCount);
        starters.add((unused) -> {
            try {
                sharedCount.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                sharedCount.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link PathChildrenCache} instance.
     * */
    public ZookeeperConnectorBuilder pathChildrenCache(PathChildrenCache pathChildrenCache) {
        requireNonNull(pathChildrenCache);
        starters.add((unused) -> {
            try {
                pathChildrenCache.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                pathChildrenCache.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link NodeCache} instance.
     * */
    public ZookeeperConnectorBuilder nodeCache(NodeCache nodeCache) {
        requireNonNull(nodeCache);
        starters.add((unused) -> {
            try {
                nodeCache.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                nodeCache.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link TreeCache} instance.
     * */
    public ZookeeperConnectorBuilder treeCache(TreeCache treeCache) {
        requireNonNull(treeCache);
        starters.add((unused) -> {
            try {
                treeCache.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> treeCache.close());
        return this;
    }

    /**
     * Add connectivity of {@link PersistentNode} instance.
     * */
    public ZookeeperConnectorBuilder persistentNode(PersistentNode persistentNode) {
        requireNonNull(persistentNode);
        starters.add((unused) -> persistentNode.start());
        stoppers.add((unused) -> {
            try {
                persistentNode.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link PersistentTtlNode} instance.
     * */
    public ZookeeperConnectorBuilder persistentTtlNode(PersistentTtlNode persistentTtlNode) {
        requireNonNull(persistentTtlNode);
        starters.add((unused) -> persistentTtlNode.start());
        stoppers.add((unused) -> persistentTtlNode.close());
        return this;
    }

    /**
     * Add connectivity of {@link GroupMember} instance.
     * */
    public ZookeeperConnectorBuilder groupMember(GroupMember groupMember) {
        requireNonNull(groupMember);
        starters.add((unused) -> groupMember.start());
        stoppers.add((unused) -> groupMember.close());
        return this;
    }

    /**
     * Add connectivity of {@link DistributedQueue} instance.
     * */
    public ZookeeperConnectorBuilder distributedQueue(DistributedQueue<?> distributedQueue) {
        requireNonNull(distributedQueue);
        starters.add((unused) -> {
            try {
                distributedQueue.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                distributedQueue.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link DistributedIdQueue} instance.
     * */
    public ZookeeperConnectorBuilder distributedIdQueue(DistributedIdQueue distributedIdQueue) {
        requireNonNull(distributedIdQueue);
        starters.add((unused) -> {
            try {
                distributedIdQueue.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                distributedIdQueue.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link DistributedPriorityQueue} instance.
     * */
    public ZookeeperConnectorBuilder distributedPriorityQueue(
            DistributedPriorityQueue distributedPriorityQueue) {
        requireNonNull(distributedPriorityQueue);
        starters.add((unused) -> {
            try {
                distributedPriorityQueue.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                distributedPriorityQueue.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Add connectivity of {@link DistributedDelayQueue} instance.
     * */
    public ZookeeperConnectorBuilder distributedDelayQueue(DistributedDelayQueue distributedDelayQueue) {
        requireNonNull(distributedDelayQueue);
        starters.add((unused) -> {
            try {
                distributedDelayQueue.start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stoppers.add((unused) -> {
            try {
                distributedDelayQueue.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return this;
    }

    /**
     * Returns a newly-created {@link ServerListener} which starts given zookeeper clients in order when
     * {@link Server} is started and closes them in reverse order when {@link Server} is stopping.
     */
    public ServerListener build() {
        return new ServerListenerBuilder()
                .addStartedCallbacks(starters)
                .addStoppingCallbacks(Lists.reverse(stoppers))
                .build();
    }
}
