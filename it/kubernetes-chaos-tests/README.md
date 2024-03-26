> This test module is forked from [kubernetes-client/chaos-test](https://github.com/fabric8io/kubernetes-client/tree/main/chaos-tests)
> and modified to run on Armeria Kubernetes Client.
 
# Chaos Mesh tests for Kubernetes Client SharedInformer

This module will run automated Chaos Tests for the SharedInformers

### Setup

Start minikube, e.g.:

```bash
minikube start --driver=docker --memory 8192 --cpus 3
```

Install ChaosMesh on minikube:

```bash
curl -sSL https://mirrors.chaos-mesh.org/v2.6.0/install.sh | bash
```

Wait for the pods to be all ready:

```bash
kubectl wait --for=condition=Ready pods -n chaos-mesh --all --timeout=600s
```

Build the control and checker Docker images in the minikube docker-env and run the test:

```bash
eval $(minikube -p minikube docker-env)
./gradlew :it:kubernetes-chaos-tests:k8sBuild :it:kubernetes-chaos-tests:test
```

Note that the test won't be run if ChaosMesh is not installed locally.

### Glossary

- checker: run the SharedInformer to get notifications over the shared resource
- control: apply timely changes to the shared resource

## What's going on?

As of today this is the main flow of the tests(please double-check the implementation if this documentation is not accurate anymore):

- Starts a "Checker" application(as a Pod in a cluster) which starts a SharedInformer on a ConfigMap
- Starts a "Control" application(as a Pod in the cluster) which keeps incrementing a counter stored in the previously mentioned ConfigMap
- The "Checker" is waiting for the number to reach a specific value N
- The "Control" keeps incrementing the counter up to the same number N
- We start a Chaos Experiment targeting the "Checker" app, in this setup, we are using 3 different Network disruption scenarios
- The Chaos Experiment lasts for 12 minutes
- After the Experiment is finished we do expect that the "Checker" app SharedInformer recovers and eventually reaches the target N
- Eventually, both the 2 Control and Checker Pods should be in a Successful state

We are testing the resiliency of the WebSocket communication used under the cover by the SharedInformers and we guarantee that they do recover appropriately when something happens.
We guarantee that we don't have regressions and this tests can be used as a gate for other HTTP client implementations.
