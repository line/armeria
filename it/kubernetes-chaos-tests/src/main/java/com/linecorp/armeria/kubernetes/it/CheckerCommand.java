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
 *
 */
/*
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.kubernetes.it;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "checker", mixinStandardHelpOptions = true)
public class CheckerCommand implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(CheckerCommand.class);

  // Forked from https://github.com/fabric8io/kubernetes-client/blob/e4947f762697be50dee06944795b58386216e8b8/chaos-tests/src/main/java/io/fabric8/it/CheckerCommand.java
  // Keep the original code as much as possible to make it easier to merge the upstream changes.

  @CommandLine.Option(names = {
      "--num" }, paramLabel = "<num>", defaultValue = "10", description = "The number to be reached to quit successfully")
  int num;

  @CommandLine.Option(names = {
      "--namespace" }, paramLabel = "<namespace>", defaultValue = "default", description = "The namespace where the configMap will be created")
  String namespace;

  @CommandLine.Option(names = {
      "--labelkey" }, paramLabel = "<labelKey>", defaultValue = "chaos", description = "The label key to match")
  String labelKey;

  @CommandLine.Option(names = {
      "--labelvalue" }, paramLabel = "<labelValue>", defaultValue = "test", description = "The label value to match")
  String labelValue;

  private static final String COUNTER = "counter";

  private int extractValue(ConfigMap configMap) {
    return Integer.parseInt(configMap.getData().get(COUNTER));
  }

  @Override
  public void run() {
    logger.info("Running Checker App - num: {}, namespace: {}", num, namespace);
    KubernetesClient client = new KubernetesClientBuilder().build();

    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger counter = new AtomicInteger();

    ResourceEventHandler<ConfigMap> handler = new ResourceEventHandler<ConfigMap>() {
      @Override
      public void onAdd(ConfigMap configMap) {
        logger.info("Ready to start");
        int initialCount = extractValue(configMap);
        if (initialCount == 0) {
          logger.info("Counter is 0");
          counter.set(0);
        } else {
          throw new RuntimeException("Counter is NOT ZERO at the start! Found value: " + initialCount);
        }
      }

      @Override
      public void onUpdate(ConfigMap oldConfigMap, ConfigMap newConfigMap) {
        int currentValue = extractValue(newConfigMap);
        if (counter.compareAndSet(currentValue - 1, currentValue)) {
          logger.info("Update received, and it's in the correct order, counter: " + currentValue);
        } else {
          if (currentValue > counter.get()) {
            logger.info("Update received, NOT in the correct order but compatible: " + currentValue);
            counter.set(currentValue);
          } else {
            throw new RuntimeException("Update received in an incorrect order: " + currentValue);
          }
        }

        if (currentValue == num) {
          logger.info("Last update received!");
        } else if (currentValue > num) {
          throw new RuntimeException("Current value is > than the expected value, " + currentValue);
        }
      }

      @Override
      public void onDelete(ConfigMap configMap, boolean deletedFinalStateUnknown) {
        if (counter.compareAndSet(num, 0)) {
          logger.info("Experiment should successfully end");
          latch.countDown();
        } else {
          throw new RuntimeException("Expected " + num + " but reached " + counter.get());
        }
      }
    };

    Map<String, String> labels = new HashMap<>();
    labels.put(labelKey, labelValue);
    Map<String, String> data = new HashMap<>();
    data.put("counter", Integer.toString(0));

    SharedIndexInformer<ConfigMap> configMapInformer = client.configMaps().inNamespace(namespace)
        .withLabel(labelKey, labelValue).inform(handler);
    configMapInformer.start();

    try {
      latch.await(30, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    logger.info("Terminating successfully!");
    System.exit(0);
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CheckerCommand()).execute(args);
    System.exit(exitCode);
  }

}
