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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.junit.jupiter.api.KubernetesTest;
import io.fabric8.junit.jupiter.api.LoadKubernetesManifests;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;

@KubernetesTest
@LoadKubernetesManifests({ "checker-infra.yaml", "control-infra.yaml" })
@EnabledIf("com.linecorp.armeria.kubernetes.it.ChaosMeshAvailableCondition#isRunning")
class ChaosIT {

  // Forked from https://github.com/fabric8io/kubernetes-client/blob/56a6c2c4f336cc6f64c19029a55c2d3d0289344f/chaos-tests/src/test/java/ChaosIT.java#L42-L42
  // Keep the original code as much as possible to make it easier to merge the upstream changes.

  private static final Logger logger = LoggerFactory.getLogger(ChaosIT.class);

  private static final String GROUP = "fabric8-chaos-tests";
  private static final String CHECKER = "checker";
  private static final String CONTROL = "control";

  private static final int TOTAL_COUNT = 1 * 60 * 20; // 1 count each second, for 20 minutes

  KubernetesClient client;

  private String checkerImage;
  private String controlImage;
  private String chaosTest;
  private String namespace;

  @BeforeEach
  void beforeEach() {
    logger.info("Preparing test suite");
    checkerImage = Optional.ofNullable(System.getenv("CHECKER_IMAGE")).orElse("chaos-test-checker:latest");
    controlImage = Optional.ofNullable(System.getenv("CONTROL_IMAGE")).orElse("chaos-test-control:latest");
    chaosTest = Optional.ofNullable(System.getenv("CHAOS_TEST")).orElse("network-delay.yaml");
    namespace = client.getNamespace();
    // this needs to be executed before the pods are started
    client.configMaps().inNamespace(namespace).withName("chaos-test").delete();
    client.pods().withLabel("group", GROUP).withGracePeriod(0L).delete();
  }

  @AfterEach
  void afterEach() {
    logger.info("Tearing down test suite");
    for (String podName : new String[] { CHECKER, CONTROL }) {
      try {
        String logs = client.pods().inNamespace(namespace).withName(podName).getLog();
        logger.info("*** {} Logs ***\n{}", podName, logs);
      } catch (Exception e) {
        // ignore
      }
    }
    client.pods().withLabel("group", GROUP).withGracePeriod(0L).delete();
  }

  // The test is expected to run for 20 minutes, so we set the timeout to 30 minutes.
  @Timeout(value = 30, unit = TimeUnit.MINUTES)
  @Test
  void test() throws IOException {
    logger.warn("Running test with chaos settings from: " + chaosTest);
    logger.warn("Using checker image: " + checkerImage);
    logger.warn("Using control image: " + controlImage);

    final PodResource checkerSelector = run(CHECKER, checkerImage);
    await().pollInterval(1, TimeUnit.SECONDS).ignoreExceptions().atMost(1, TimeUnit.MINUTES).until(() -> {
      assertEquals("Running", checkerSelector.get().getStatus().getPhase());
      return true;
    });

    final PodResource controlSelector = run(CONTROL, controlImage);
    await().pollInterval(1, TimeUnit.SECONDS).ignoreExceptions().atMost(1, TimeUnit.MINUTES).until(() -> {
      assertTrue(checkerSelector.getLog().contains("Update received, and it's in the correct order, counter: 1"));
      return true;
    });

    try (InputStream is = this.getClass().getResourceAsStream(chaosTest)) {
      client.load(is).inNamespace(client.getNamespace()).serverSideApply();
    }

    await().pollInterval(10, TimeUnit.SECONDS).ignoreExceptions().atMost(30, TimeUnit.MINUTES).until(() -> {
      logger.info("Checking status");
      logger.info("checker: " + checkerSelector.get().getStatus().getPhase());
      logger.info("control: " + controlSelector.get().getStatus().getPhase());
      assertEquals("Succeeded", checkerSelector.get().getStatus().getPhase());
      assertEquals("Succeeded", controlSelector.get().getStatus().getPhase());
      return true;
    });
  }

  private PodResource run(String name, String image) {
    client.resource(new PodBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(name)
            .addToLabels("app", name)
            .addToLabels("group", GROUP)
            .build())
        .withSpec(new PodSpecBuilder()
            .addNewContainer()
            .withName(name)
            .withImagePullPolicy("Never")
            .withImage(image)
            .addToEnv(
                new EnvVarBuilder()
                    .withName("JAVA_ARGS")
                    .withValue("--num " + TOTAL_COUNT + " --namespace " + namespace)
                    .build())
            .endContainer()
            .withRestartPolicy("Never")
            .withServiceAccount("chaos-test-" + name + "-sa")
            .build())
        .build())
        .serverSideApply();
    return client.pods().inNamespace(namespace).withName(name);
  }
}
