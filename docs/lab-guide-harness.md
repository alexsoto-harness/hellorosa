# Lab Guide: Deploying to ROSA with Harness CI/CD

## Overview

This guide walks you through setting up a full **Harness CI/CD pipeline** to build and deploy a Spring Boot application to **ROSA (Red Hat OpenShift Service on AWS)**. This includes installing a Harness Delegate on the cluster, creating all required Harness resources, and running a pipeline that builds a Docker image and deploys it via a Kubernetes rolling update.

**Time to complete:** ~30 minutes

---

## Prerequisites

- ROSA cluster access with `oc` CLI authenticated
- Harness account with CI and CD modules licensed
- Docker Hub account
- Application source code in a GitHub repository with a Dockerfile
- Helm v3 installed locally

---

## Key Concepts

### What is Harness?

Harness is a CI/CD platform that automates building, testing, and deploying software. It uses a **Delegate** model where a lightweight agent runs in your infrastructure and executes pipeline tasks on behalf of the Harness platform.

### Harness Resources

| Resource | Description |
|----------|-------------|
| **Delegate** | Agent that runs in your cluster and executes pipeline tasks |
| **Connector** | Connection to external systems (Git, Docker Hub, K8s cluster) |
| **Service** | Defines your application: its manifests and artifacts |
| **Environment** | Target deployment environment (dev, staging, prod) |
| **Infrastructure** | Specific cluster/namespace within an environment |
| **Pipeline** | Workflow with CI (build) and CD (deploy) stages |

### Architecture

```
                        Harness Platform (SaaS)
                     ┌──────────────────────────────┐
                     │  Pipeline Orchestration       │
                     │  ┌────────────┐ ┌───────────┐ │
                     │  │  CI Stage  │ │ CD Stage  │ │
                     │  │ (Harness   │ │ (deploys  │ │
                     │  │  Cloud)    │ │  via      │ │
                     │  │            │ │  delegate)│ │
                     │  └─────┬──────┘ └─────┬─────┘ │
                     └────────┼──────────────┼───────┘
                              │              │
                    ┌─────────▼──┐    ┌──────▼──────┐
                    │ Docker Hub │    │ ROSA Cluster│
                    │ (push      │    │             │
                    │  image)    │    │ ┌─────────┐ │
                    └────────────┘    │ │Delegate │ │
                                      │ │(executes│ │
                                      │ │ deploy) │ │
                                      │ └────┬────┘ │
                                      │      │      │
                                      │ ┌────▼────┐ │
                                      │ │ App Pod │ │
                                      │ └─────────┘ │
                                      └─────────────┘
```

---

## Step 1: Install Harness Delegate on ROSA

The Delegate is a pod that runs inside your ROSA cluster. It communicates with Harness SaaS and executes deployment commands.

### OpenShift Considerations

OpenShift has stricter security defaults than vanilla Kubernetes. The Delegate Helm chart needs a `securityContext` to run as a non-root user:

```yaml
securityContext:
  runAsUser: 1000
  runAsGroup: 3000
  fsGroup: 2000
```

### Create the Namespace

```bash
oc new-project harness-delegate-ng --description="Harness Delegate"
```

### Get Your Delegate Token

1. In Harness, go to **Account Settings > Account Resources > Delegates > Tokens**
2. Copy an existing token or create a new one

### Install via Helm

```bash
# Add the Harness Helm repo
helm repo add harness-delegate \
  https://app.harness.io/storage/harness-download/delegate-helm-chart/
helm repo update

# Install the delegate
helm upgrade -i rosa-delegate \
  --namespace harness-delegate-ng \
  harness-delegate/harness-delegate-ng \
  --set delegateName=rosa-delegate \
  --set accountId=<YOUR_ACCOUNT_ID> \
  --set delegateToken=<YOUR_DELEGATE_TOKEN> \
  --set managerEndpoint=https://app.harness.io/gratis \
  --set delegateDockerImage=harness/delegate:26.02.88404 \
  --set replicas=1 \
  --set upgrader.enabled=true \
  --set securityContext.runAsUser=1000 \
  --set securityContext.runAsGroup=3000 \
  --set securityContext.fsGroup=2000
```

**Important Notes:**
- Use `/gratis` in the manager endpoint for free-tier accounts, or just `https://app.harness.io` for paid accounts
- Use a known delegate image version (check your other delegates or Harness release notes)
- The `securityContext` settings are required for OpenShift compatibility

### Verify

```bash
# Check the delegate pod is running
oc get pods -n harness-delegate-ng

# Check logs for successful registration
oc logs -n harness-delegate-ng -l app.kubernetes.io/name=rosa-delegate --tail=20
```

Confirm the delegate appears as **Connected** in the Harness UI under **Account Settings > Delegates**.

---

## Step 2: Create Kubernetes Connector

The K8s Cluster Connector tells Harness how to connect to your ROSA cluster. Since the Delegate runs inside the cluster, it can use `InheritFromDelegate` credentials.

Create the connector via Harness API or UI:

```json
{
  "connector": {
    "name": "rosa-cluster",
    "identifier": "rosacluster",
    "orgIdentifier": "<ORG>",
    "projectIdentifier": "<PROJECT>",
    "type": "K8sCluster",
    "spec": {
      "credential": {
        "type": "InheritFromDelegate"
      },
      "delegateSelectors": ["rosa-delegate"]
    }
  }
}
```

**Key Point:** `InheritFromDelegate` means the connector uses the Delegate's own service account to access the cluster. No additional credentials needed.

---

## Step 3: Create the Application Namespace

Create the OpenShift project where your app will be deployed:

```bash
oc new-project hello-rosa-harness --description="Harness CI/CD deployment"
```

---

## Step 4: Prepare Kubernetes Manifests

Harness uses Go templates to inject values into your Kubernetes manifests. The critical rule:

> **Harness expressions (`<+artifact.image>`, etc.) can only be used in `values.yaml`, not directly in K8s manifest files.**

### values.yaml

```yaml
image: <+artifact.image>
deploymentMethod: harness
```

Harness resolves `<+artifact.image>` to the full image reference (e.g., `alexsotoharness/hello-rosa:6`) based on the artifact configured in the Service.

### deployment.yaml

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hello-rosa-harness
  labels:
    app: hello-rosa-harness
spec:
  replicas: 1
  selector:
    matchLabels:
      app: hello-rosa-harness
  template:
    metadata:
      labels:
        app: hello-rosa-harness
    spec:
      containers:
        - name: hello-rosa
          image: {{.Values.image}}
          ports:
            - containerPort: 8080
          env:
            - name: DEPLOYMENT_METHOD
              value: {{.Values.deploymentMethod}}
```

### service.yaml

```yaml
apiVersion: v1
kind: Service
metadata:
  name: hello-rosa-harness
spec:
  type: ClusterIP
  ports:
    - port: 8080
      targetPort: 8080
  selector:
    app: hello-rosa-harness
```

### route.yaml (OpenShift-specific)

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: hello-rosa-harness
spec:
  port:
    targetPort: 8080
  tls:
    termination: edge
  to:
    kind: Service
    name: hello-rosa-harness
    weight: 100
```

Push all manifests to your Git repo under a `k8s/` directory.

---

## Step 5: Create Harness Service

The Service defines what you're deploying: manifests + artifact source.

```yaml
service:
  name: hello-rosa
  identifier: hellorosa
  serviceDefinition:
    type: Kubernetes
    spec:
      manifests:
        - manifest:
            identifier: k8smanifests
            type: K8sManifest
            spec:
              store:
                type: Github
                spec:
                  connectorRef: <github-connector-id>
                  repoName: <your-repo>
                  gitFetchType: Branch
                  branch: main
                  paths:
                    - k8s
              skipResourceVersioning: false
      artifacts:
        primary:
          primaryArtifactRef: dockerhub
          sources:
            - identifier: dockerhub
              spec:
                connectorRef: <dockerhub-connector-id>
                imagePath: <dockerhub-user>/hello-rosa
                tag: <+input>
              type: DockerRegistry
```

The `tag: <+input>` makes the tag a runtime input that the pipeline provides.

---

## Step 6: Create Environment and Infrastructure

### Environment

```yaml
environment:
  name: rosa-dev
  identifier: rosadev
  type: PreProduction
```

### Infrastructure Definition

```yaml
infrastructureDefinition:
  name: rosa-infra
  identifier: rosainfra
  environmentRef: rosadev
  deploymentType: Kubernetes
  type: KubernetesDirect
  spec:
    connectorRef: rosacluster
    namespace: hello-rosa-harness
    releaseName: release-<+INFRA_KEY_SHORT_ID>
```

---

## Step 7: Create the CI/CD Pipeline

```yaml
pipeline:
  name: hello-rosa-cicd
  identifier: hellorosacicd
  stages:
    - stage:
        name: Build and Push
        identifier: build_and_push
        type: CI
        spec:
          cloneCodebase: true
          platform:
            os: Linux
            arch: Amd64
          runtime:
            type: Cloud
            spec: {}
          execution:
            steps:
              - step:
                  type: Run
                  name: Maven Build
                  identifier: maven_build
                  spec:
                    shell: Sh
                    command: |
                      export JAVA_HOME=$JAVA_HOME_17_X64
                      export PATH=$JAVA_HOME/bin:$PATH
                      java -version
                      mvn clean package -DskipTests
              - step:
                  type: BuildAndPushDockerRegistry
                  name: Build and Push to DockerHub
                  identifier: build_push
                  spec:
                    connectorRef: <dockerhub-connector-id>
                    repo: <dockerhub-user>/hello-rosa
                    tags:
                      - <+pipeline.sequenceId>
                      - latest
                    dockerfile: Dockerfile.harness
                    platform:
                      os: Linux
                      arch: Amd64
          caching:
            enabled: true
            paths: []
    - stage:
        name: Deploy to ROSA
        identifier: deploy_to_rosa
        type: Deployment
        spec:
          deploymentType: Kubernetes
          service:
            serviceRef: hellorosa
            serviceInputs:
              serviceDefinition:
                type: Kubernetes
                spec:
                  artifacts:
                    primary:
                      primaryArtifactRef: dockerhub
                      sources:
                        - identifier: dockerhub
                          type: DockerRegistry
                          spec:
                            tag: <+pipeline.sequenceId>
          environment:
            environmentRef: rosadev
            deployToAll: false
            infrastructureDefinitions:
              - identifier: rosainfra
          execution:
            steps:
              - step:
                  name: Rolling Deployment
                  identifier: rollingDeployment
                  type: K8sRollingDeploy
                  timeout: 10m
                  spec:
                    skipDryRun: false
            rollbackSteps:
              - step:
                  name: Rolling Rollback
                  identifier: rollingRollback
                  type: K8sRollingRollback
                  timeout: 10m
          failureStrategies:
            - onFailure:
                errors:
                  - AllErrors
                action:
                  type: StageRollback
  properties:
    ci:
      codebase:
        connectorRef: <github-connector-id>
        repoName: <your-repo>
        build: <+input>
```

### Pipeline Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| **CI infrastructure** | Harness Cloud | OpenShift restricts Docker-in-Docker (privileged containers), so building on the cluster fails. Harness Cloud handles Docker builds natively. |
| **Split build steps** | Maven Run + BuildAndPush | Maven runs on the host (enabling Cache Intelligence for `.m2`), then a lightweight `Dockerfile.harness` copies the pre-built JAR into a JRE image. |
| **Java version** | `$JAVA_HOME_17_X64` | Harness Cloud comes pre-installed with Java 8, 11, **17 (default)**, and 21. No custom Docker image needed. |
| **CD infrastructure** | ROSA via Delegate | The Delegate runs inside the cluster and has direct access to deploy. |
| **Image tag** | `<+pipeline.sequenceId>` | Auto-incrementing number per pipeline run. Ensures unique, traceable tags. |
| **Artifact in manifest** | `values.yaml` + Go templates | Harness expressions only resolve in `values.yaml`, not in raw K8s manifests. |

### Harness Cloud Pre-installed Tools

Harness Cloud VMs come with a rich set of pre-installed tools. Key ones for Java builds:

| Tool | Version | Notes |
|------|---------|-------|
| **Java 17** | 17.0.15+6 | Default. Use `$JAVA_HOME_17_X64` |
| **Java 21** | 21.0.7+6 | Use `$JAVA_HOME_21_X64` |
| **Maven** | 3.9.9 | Pre-installed, no image needed |
| **Docker** | 28.0.4 | Native Docker builds |
| **kubectl** | 1.33.0 | For K8s operations |
| **Helm** | 3.17.3 | For Helm chart deployments |

Full list: [Harness Cloud Ubuntu 24.04 Readme](https://github.com/wings-software/harness-docs/blob/main/harness-cloud/Linux-amd/Ubuntu2404-Readme.md)

### Cache Intelligence

Cache Intelligence automatically caches and restores Maven dependencies (`.m2/repository`) between builds. To enable it:

1. **Split your build** — Maven must run directly on the host (not inside a Docker build). Use a `Run` step for `mvn package`, then a `BuildAndPush` step with a lightweight `Dockerfile.harness` that just copies the JAR.

2. **Enable caching** in the stage spec:
   ```yaml
   caching:
     enabled: true
     paths: []
   ```

3. **Use a lightweight Dockerfile** (`Dockerfile.harness`) for the push step:
   ```dockerfile
   FROM eclipse-temurin:21-jre-alpine
   WORKDIR /app
   COPY target/*.jar app.jar
   EXPOSE 8080
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```

**First run:** Cache Intelligence saves `.m2/repository` after the Maven step.
**Subsequent runs:** Dependencies are restored from cache, skipping downloads entirely.

---

## Step 8: Run the Pipeline

Trigger via UI or API:

```bash
curl -X POST \
  'https://app.harness.io/gateway/pipeline/api/pipeline/execute/<pipeline-id>?accountIdentifier=<account>&orgIdentifier=<org>&projectIdentifier=<project>&moduleType=CI' \
  -H 'Content-Type: application/yaml' \
  -H 'x-api-key: <your-api-key>' \
  --data-binary 'pipeline:
  properties:
    ci:
      codebase:
        build:
          type: branch
          spec:
            branch: main'
```

### What Happens

1. **CI Stage** (Harness Cloud):
   - Restores `.m2` cache (if available from prior runs)
   - Clones the GitHub repo
   - **Maven Build** step: compiles and packages the JAR using pre-installed Java 17 + Maven 3.9
   - **Build and Push** step: builds a lightweight Docker image using `Dockerfile.harness` (just copies the JAR into a JRE base) and pushes to Docker Hub with tags `<sequenceId>` and `latest`
   - Saves `.m2` cache for next run

2. **CD Stage** (ROSA via Delegate):
   - Fetches K8s manifests from GitHub
   - Resolves `<+artifact.image>` in `values.yaml` to the pushed image
   - Renders Go templates (`{{.Values.image}}`) in manifests
   - Applies manifests via `oc apply`
   - Waits for steady state (pod Running + passing readiness probes)

---

## Troubleshooting

### Delegate Not Connecting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `EncryptDecryptException` | Wrong token format | Use the raw token from Harness UI, not base64-encoded |
| 401 errors in delegate logs | Wrong manager endpoint | Use `https://app.harness.io/gratis` for free-tier |
| `ImagePullBackOff` | Bad delegate image tag | Use a known version like `26.02.88404` |
| Pod not starting on OpenShift | Missing securityContext | Add `runAsUser/runAsGroup/fsGroup` to Helm values |

### CI Build Fails on ROSA

**Error:** `insufficient resources` or privileged container errors

**Cause:** OpenShift doesn't allow Docker-in-Docker (privileged containers needed for building images).

**Fix:** Use **Harness Cloud** for the CI stage:

```yaml
platform:
  os: Linux
  arch: Amd64
runtime:
  type: Cloud
  spec: {}
```

### Maven Build Fails: "release version 17 not supported"

**Cause:** The Run step is using a Docker image with an older JDK, or the default `JAVA_HOME` on Harness Cloud points to Java 11.

**Fix:** Use the pre-installed Java 17 on Harness Cloud by setting `JAVA_HOME`:

```sh
export JAVA_HOME=$JAVA_HOME_17_X64
export PATH=$JAVA_HOME/bin:$PATH
```

Do **not** specify a custom Docker image for the Run step — run Maven directly on the host so Cache Intelligence can see `.m2/repository`.

Available Java versions on Harness Cloud: `$JAVA_HOME_8_X64`, `$JAVA_HOME_11_X64`, `$JAVA_HOME_17_X64`, `$JAVA_HOME_21_X64`.

### Cache Intelligence Not Working

**Symptom:** `IgnoreFailed` status on the CI stage, or dependencies are downloaded every run.

**Cause:** Maven runs inside a Docker build (multi-stage Dockerfile) or inside a container image step, so Cache Intelligence can't see the `.m2` directory on the host.

**Fix:** Split the build into two steps:
1. **Run step** — `mvn clean package` on the host (no Docker image specified)
2. **BuildAndPush step** — uses a lightweight `Dockerfile.harness` that just copies the pre-built JAR

### `<+artifact.image>` Not Resolved

**Error:** `InvalidImageName: couldn't parse image name "<+artifact.image>"`

**Cause:** Harness expressions placed directly in K8s manifest files.

**Fix:** Move expressions to `values.yaml` and use Go templates in manifests:

```yaml
# values.yaml
image: <+artifact.image>

# deployment.yaml
image: {{.Values.image}}
```

### Connector Created at Wrong Scope

**Symptom:** Connector appears in Account Settings instead of your project.

**Fix:** Include `orgIdentifier` and `projectIdentifier` in the connector JSON body when creating via API.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Harness Platform (SaaS)                          │
│                                                                         │
│  ┌─────────────────────────┐    ┌────────────────────────────────────┐  │
│  │    CI Stage             │    │    CD Stage                        │  │
│  │  (Harness Cloud)        │    │  (Orchestrated via Delegate)       │  │
│  │                         │    │                                    │  │
│  │  1. Clone repo          │    │  1. Fetch manifests from Git       │  │
│  │  2. Docker build (amd64)│    │  2. Resolve values.yaml            │  │
│  │  3. Push to Docker Hub  │    │  3. Render Go templates            │  │
│  │                         │    │  4. oc apply manifests              │  │
│  └────────────┬────────────┘    │  5. Wait for steady state          │  │
│               │                 └──────────────┬─────────────────────┘  │
└───────────────┼────────────────────────────────┼────────────────────────┘
                │                                │
                ▼                                ▼
     ┌─────────────────┐             ┌───────────────────────────────────┐
     │   Docker Hub    │             │          ROSA Cluster             │
     │                 │             │                                   │
     │ hello-rosa:6    │             │  ┌───────────────────────────┐    │
     │ hello-rosa:     │             │  │ NS: harness-delegate-ng   │    │
     │   latest        │             │  │  ┌─────────────────────┐  │    │
     └─────────────────┘             │  │  │  Delegate Pod       │  │    │
                                     │  │  │  (executes deploy)  │  │    │
                                     │  │  └─────────────────────┘  │    │
                                     │  └───────────────────────────┘    │
                                     │                                   │
                                     │  ┌───────────────────────────┐    │
                                     │  │ NS: hello-rosa-harness    │    │
                                     │  │  ┌──────┐ ┌───────┐      │    │
                                     │  │  │ Pod  │ │Service│      │    │
                                     │  │  └──────┘ └───┬───┘      │    │
                                     │  │               │          │    │
                                     │  │          ┌────▼────┐     │    │
                                     │  │          │  Route  │     │    │
                                     │  │          │(edge TLS│     │    │
                                     │  │          └─────────┘     │    │
                                     │  └───────────────────────────┘    │
                                     └───────────────────────────────────┘
                                                    │
                                                    ▼
                                         ┌─────────────────────┐
                                         │   External Users    │
                                         └─────────────────────┘
```
