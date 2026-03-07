# Lab Guide: Deploying to ROSA using Dockerfile + External Registry

## Overview

This guide walks you through deploying a Spring Boot application to **Red Hat OpenShift Service on AWS (ROSA)** by building a container image locally and pushing it to an external registry (Docker Hub).

**Time to complete:** ~10 minutes

---

## Prerequisites

- ROSA cluster access with `oc` CLI authenticated
- Docker installed locally
- Docker Hub account (or other container registry)
- Application source code with a Dockerfile

---

## Key Concepts

### How This Differs from S2I

| Aspect | S2I | Dockerfile + Registry |
|--------|-----|----------------------|
| **Build location** | On the cluster | On your local machine |
| **Dockerfile needed?** | No | Yes |
| **Registry** | OpenShift internal | External (Docker Hub, Quay, etc.) |
| **Control** | OpenShift decides how to build | You control the entire build |
| **Java version** | Limited to available builder images | Any version you want |

### Why Use This Approach?

- **Full control** over the build process and base images
- **Custom dependencies** that S2I builders don't support
- **Specific runtime versions** (e.g., Java 21 when S2I only has Java 17)
- **Existing CI/CD pipelines** that already build images
- **Multi-stage builds** for optimized image sizes

---

## Step-by-Step Deployment

### Step 1: Create a Dockerfile

A multi-stage Dockerfile for Spring Boot:

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Why multi-stage?**
- Build stage: Has Maven + JDK (large, ~400MB)
- Runtime stage: Only JRE + your JAR (small, ~100MB)

### Step 2: Build the Image

```bash
# Build for AMD64 (required for ROSA)
docker build --platform linux/amd64 -t <dockerhub-username>/hello-rosa:dockerfile .
```

| Flag | Purpose |
|------|---------|
| `--platform linux/amd64` | Target architecture for ROSA nodes |
| `-t <name>:<tag>` | Image name and tag |
| `.` | Build context (current directory) |

### Step 3: Push to Docker Hub

```bash
# Login to Docker Hub (if not already)
docker login

# Push the image
docker push <dockerhub-username>/hello-rosa:dockerfile
```

### Step 4: Create OpenShift Project

```bash
oc new-project hello-rosa-dockerfile --description="Dockerfile deployment demo"
```

### Step 5: Deploy to ROSA

```bash
oc new-app docker.io/<username>/hello-rosa:dockerfile \
  --name=hello-rosa-dockerfile \
  -e DEPLOYMENT_METHOD=dockerfile
```

### Step 6: Expose the Service

```bash
oc create route edge hello-rosa-dockerfile \
  --service=hello-rosa-dockerfile \
  --port=8080
```

### Step 7: Access Your Application

```bash
# Get the route URL
oc get route hello-rosa-dockerfile -o jsonpath='{.spec.host}'
```

Your app is available at:
```
https://<route-name>-<project>.apps.rosa.<cluster-domain>
```

---

## Useful Commands Reference

### Docker Commands
```bash
# List local images
docker images | grep hello-rosa

# Check image architecture
docker inspect <image> | grep Architecture

# Build for multiple architectures
docker buildx build --platform linux/amd64,linux/arm64 -t <image> --push .
```

### Deployment Management
```bash
# View deployment status
kubectl get deployment hello-rosa-dockerfile

# Check pod logs
kubectl logs deployment/hello-rosa-dockerfile

# Scale replicas
kubectl scale deployment/hello-rosa-dockerfile --replicas=3

# Update image (triggers rollout)
kubectl set image deployment/hello-rosa-dockerfile \
  hello-rosa-dockerfile=<new-image>:<new-tag>

# Rollback to previous version
kubectl rollout undo deployment/hello-rosa-dockerfile
```

### Debugging
```bash
# Describe pod for events
kubectl describe pod <pod-name>

# Get shell in running container
kubectl exec -it <pod-name> -- /bin/sh

# Check if image was pulled correctly
kubectl get pod <pod-name> -o jsonpath='{.status.containerStatuses[0].imageID}'
```

---

## Troubleshooting

### "exec format error"

**Cause:** Image built for wrong architecture (ARM vs AMD64).

**Fix:**
```bash
docker build --platform linux/amd64 -t <image> .
docker push <image>
kubectl rollout restart deployment/<name>
```

### ImageStream Caching Old Image

**Cause:** `oc new-app` creates an ImageStream that pins to a specific SHA.

**Fixes:**
1. Use a new tag:
   ```bash
   docker tag <image>:old <image>:new
   docker push <image>:new
   oc new-app <image>:new
   ```

2. Import the updated image:
   ```bash
   oc import-image <imagestream>:<tag> --from=<image>:<tag> --confirm
   ```

3. Use `kubectl create deployment` instead (no ImageStream).

### ImagePullBackOff

**Cause:** Can't pull image from registry.

**Check:**
- Image name/tag is correct
- Image is public (or you've configured pull secrets)
- Registry is accessible

**For private registries:**
```bash
# Create pull secret
kubectl create secret docker-registry my-registry-secret \
  --docker-server=docker.io \
  --docker-username=<username> \
  --docker-password=<password>

# Link to default service account
kubectl patch serviceaccount default \
  -p '{"imagePullSecrets": [{"name": "my-registry-secret"}]}'
```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      Developer Workstation                      │
│                                                                 │
│   ┌─────────────┐      ┌─────────────┐      ┌─────────────┐    │
│   │  Source     │─────▶│   Docker    │─────▶│   Docker    │    │
│   │  Code +     │      │   Build     │      │   Push      │    │
│   │  Dockerfile │      │ (amd64)     │      │             │    │
│   └─────────────┘      └─────────────┘      └──────┬──────┘    │
│                                                    │           │
└────────────────────────────────────────────────────┼───────────┘
                                                     │
                                                     ▼
                                          ┌─────────────────────┐
                                          │     Docker Hub      │
                                          │  (External Registry)│
                                          └──────────┬──────────┘
                                                     │
                                                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                         ROSA Cluster                            │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                 Project: hello-rosa-dockerfile            │  │
│  │                                                           │  │
│  │   ┌─────────────┐      ┌─────────────┐                   │  │
│  │   │ Deployment  │─────▶│  App Pod(s) │◀──┐               │  │
│  │   │             │      │ (pulls from │   │               │  │
│  │   │             │      │  Docker Hub)│   │               │  │
│  │   └─────────────┘      └─────────────┘   │               │  │
│  │                                          │               │  │
│  │                        ┌─────────────┐   │               │  │
│  │                        │   Service   │───┘               │  │
│  │                        │ (ClusterIP) │                   │  │
│  │                        └──────┬──────┘                   │  │
│  │                               │                          │  │
│  │                        ┌──────▼──────┐                   │  │
│  │                        │    Route    │                   │  │
│  │                        │ (edge TLS)  │                   │  │
│  │                        └─────────────┘                   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │   External Users    │
                    │ https://app.rosa... │
                    └─────────────────────┘
```

---

## Comparison: S2I vs Dockerfile

| Aspect | S2I | Dockerfile |
|--------|-----|------------|
| **Build location** | Cluster | Local machine |
| **Build time** | Uses cluster resources | Uses your machine |
| **Dockerfile needed** | No | Yes |
| **Customization** | Limited to builder image | Full control |
| **Registry** | Internal (automatic) | External (you manage) |
| **CI/CD integration** | Built-in triggers | Use your own pipeline |
| **Learning curve** | Lower | Higher |
| **Best for** | Quick deployments, standard stacks | Custom builds, specific requirements |
