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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "control", mixinStandardHelpOptions = true)
public class ControlCommand implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(ControlCommand.class);

  // Forked from https://github.com/fabric8io/kubernetes-client/blob/e4947f762697be50dee06944795b58386216e8b8/chaos-tests/src/main/java/io/fabric8/it/ControlCommand.java
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

  @CommandLine.Option(names = {
      "--delay" }, paramLabel = "<delay>", defaultValue = "1000", description = "The delay between each number increase")
  int delay;

  private static final String COUNTER = "counter";

  private int extractValue(ConfigMap configMap) {
    return Integer.parseInt(configMap.getData().get(COUNTER));
  }

  @Override
  public void run() {
    logger.info("Running Control App - num: {}, namespace: {}", num, namespace);
    KubernetesClient client = new KubernetesClientBuilder().build();

    Map<String, String> labels = new HashMap<>();
    labels.put(labelKey, labelValue);
    Map<String, String> data = new HashMap<>();
    data.put("counter", Integer.toString(0));
    ConfigMap defaultConfigMap = new ConfigMapBuilder()
        .withNewMetadata()
        .withName("chaos-test")
        .withNamespace(namespace)
        .withLabels(labels)
        .endMetadata()
        .withData(data)
        .build();

    if (client.resource(defaultConfigMap).inNamespace(namespace).get() != null) {
      logger.debug("ConfigMap detected removing it before starting");
      client.resource(defaultConfigMap).inNamespace(namespace).delete();
    }
    client.resource(defaultConfigMap).inNamespace(namespace).create();

    while (true) {
      try {
        Thread.sleep(delay);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      ConfigMap currentConfigMap = client.resource(defaultConfigMap).inNamespace(namespace).get();

      if (currentConfigMap == null) {
        throw new RuntimeException("Cannot find the configMap!");
      } else {
        int counter = extractValue(currentConfigMap);
        logger.debug("going to increment the value, current: " + counter);

        if (counter == num) {
          logger.debug("I'm done here!");
          break;
        } else if (counter > num) {
          throw new RuntimeException("Something went wrong!");
        } else {
          currentConfigMap.getData().put(COUNTER, Integer.toString(counter + 1));
          client.resource(currentConfigMap).inNamespace(namespace).createOrReplace();
          System.out.println("Counter incremented");
        }
      }
    }

    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    logger.info("Finished! deleting the ConfigMap");
    client.resource(defaultConfigMap).inNamespace(namespace).delete();
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new ControlCommand()).execute(args);
    System.exit(exitCode);
  }

}
