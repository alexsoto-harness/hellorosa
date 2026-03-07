# Lab Guide: Deploying a Spring Boot App to ROSA using S2I

## Overview

This guide walks you through deploying a Spring Boot application to **Red Hat OpenShift Service on AWS (ROSA)** using the **Source-to-Image (S2I)** build strategy.

**Time to complete:** ~15 minutes

---

## Prerequisites

- ROSA cluster access with `oc` CLI authenticated
- Application source code in a Git repository
- Basic familiarity with terminal commands

---

## Key Concepts

### What is ROSA?
**Red Hat OpenShift Service on AWS** is a fully managed OpenShift service running on AWS infrastructure. It provides enterprise Kubernetes with additional features like built-in CI/CD, developer tools, and operational automation.

### What is S2I (Source-to-Image)?
S2I is an OpenShift build strategy that:
1. Takes your source code from a Git repository
2. Detects the language/framework (Java, Node.js, Python, etc.)
3. Builds a container image automatically
4. Pushes it to OpenShift's internal registry
5. Deploys it to the cluster

**No Dockerfile required!** OpenShift handles containerization for you.

### OpenShift Resources Explained

| Resource | Description |
|----------|-------------|
| **Project** | A namespace with additional features (RBAC, quotas). Isolates your apps. |
| **BuildConfig** | Defines how to build your container image (source repo, build strategy, triggers). |
| **Build Pod** | A temporary pod that runs the build process. Shows as `*-build` in pod list. |
| **ImageStream** | A pointer to container images. Tracks image versions and triggers redeployments. |
| **Deployment** | Manages your application pods (replicas, rolling updates, rollbacks). |
| **Service** | Internal load balancer that routes traffic to your pods via ClusterIP. |
| **Route** | Exposes your Service externally with a public URL and optional TLS. |

---

## Step-by-Step Deployment

### Step 1: Authenticate to ROSA

```bash
# Login to OpenShift (opens browser for authentication)
oc login https://api.<cluster-domain>:443 --web
```

Verify you're connected:
```bash
oc whoami
oc cluster-info
```

### Step 2: Create a Project

A **Project** is an isolated namespace for your application.

```bash
oc new-project hello-rosa-s2i --description="S2I deployment demo"
```

This:
- Creates a new namespace called `hello-rosa-s2i`
- Switches your context to use this project
- Sets up default RBAC policies

### Step 3: Deploy with S2I

The `oc new-app` command does everything in one step:

```bash
oc new-app \
  --image-stream="openshift/java:openjdk-17-ubi8" \
  ~https://github.com/YOUR-USERNAME/YOUR-REPO.git \
  --name=hello-rosa-s2i \
  -e DEPLOYMENT_METHOD=s2i
```

**Breaking it down:**

| Part | Meaning |
|------|---------|
| `--image-stream="openshift/java:openjdk-17-ubi8"` | Use the Java 17 S2I builder image |
| `~https://github.com/...` | The `~` indicates "build from this source" |
| `--name=hello-rosa-s2i` | Name for all created resources |
| `-e DEPLOYMENT_METHOD=s2i` | Set an environment variable in the container |

**What gets created:**
- `BuildConfig` - Instructions for building the image
- `ImageStream` - Reference to the built image
- `Deployment` - Manages the running pods
- `Service` - Internal networking (ClusterIP)

### Step 4: Monitor the Build

Watch the build progress:
```bash
oc logs -f buildconfig/hello-rosa-s2i
```

Check build status:
```bash
oc get builds
```

You'll see a **build pod** (e.g., `hello-rosa-s2i-1-build`) that:
1. Clones your Git repo
2. Runs Maven/Gradle to compile
3. Creates a container image
4. Pushes to the internal registry
5. Terminates (status: `Completed`)

### Step 5: Verify Deployment

Check all resources:
```bash
oc get pods,svc,deployment
```

Expected output:
```
NAME                                  READY   STATUS      RESTARTS   AGE
pod/hello-rosa-s2i-1-build            0/1     Completed   0          5m
pod/hello-rosa-s2i-abc123-xyz         1/1     Running     0          2m

NAME                     TYPE        CLUSTER-IP      PORT(S)
service/hello-rosa-s2i   ClusterIP   172.30.x.x      8080/TCP

NAME                             READY   UP-TO-DATE   AVAILABLE
deployment.apps/hello-rosa-s2i   1/1     1            1
```

### Step 6: Expose the Application

By default, your app is only accessible inside the cluster. Create a **Route** to expose it externally.

**HTTP Route:**
```bash
oc expose service/hello-rosa-s2i --port=8080
```

**HTTPS Route (recommended):**
```bash
oc create route edge hello-rosa-s2i-tls --service=hello-rosa-s2i --port=8080
```

| Route Type | Description |
|------------|-------------|
| `edge` | TLS terminates at the router; traffic to pod is HTTP |
| `passthrough` | TLS passes through to the pod (pod must handle TLS) |
| `reencrypt` | TLS terminates at router, re-encrypts to pod |

### Step 7: Access Your Application

Get the route URL:
```bash
oc get route
```

Your app is now available at:
```
https://<route-name>-<project>.apps.rosa.<cluster-domain>
```

---

## Useful Commands Reference

### Build Management
```bash
# Start a new build
oc start-build hello-rosa-s2i

# Start build and follow logs
oc start-build hello-rosa-s2i --follow

# View build logs
oc logs build/hello-rosa-s2i-1
```

### Debugging
```bash
# View pod logs
oc logs deployment/hello-rosa-s2i

# Get a shell in the running pod
oc rsh deployment/hello-rosa-s2i

# Describe resources for troubleshooting
oc describe pod <pod-name>
oc describe build <build-name>
```

### Scaling
```bash
# Scale to 3 replicas
oc scale deployment/hello-rosa-s2i --replicas=3
```

### Cleanup
```bash
# Delete all resources in the project
oc delete project hello-rosa-s2i
```

---

## Troubleshooting

### Build Failed
1. Check build logs: `oc logs build/hello-rosa-s2i-1`
2. Common issues:
   - Java version mismatch (use Java 17 for `openjdk-17-ubi8` builder)
   - Missing `pom.xml` or `build.gradle`
   - Private repo without credentials

### Application Not Available (503)
1. Check pod status: `oc get pods`
2. Check pod logs: `oc logs <pod-name>`
3. Verify endpoints exist: `oc get endpoints`
4. For HTTPS, ensure you created an `edge` route

### Route Not Working
1. Verify route exists: `oc get route`
2. Check route status: `oc describe route <route-name>`
3. Test internally first: 
   ```bash
   oc run curl --rm -i --restart=Never --image=curlimages/curl \
     -- curl http://<service-name>:8080/api/health
   ```

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                         ROSA Cluster                            │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    Project: hello-rosa-s2i                │  │
│  │                                                           │  │
│  │   ┌─────────────┐      ┌─────────────┐                   │  │
│  │   │ BuildConfig │─────▶│ Build Pod   │                   │  │
│  │   └─────────────┘      │ (temporary) │                   │  │
│  │         │              └──────┬──────┘                   │  │
│  │         │                     │                          │  │
│  │         ▼                     ▼                          │  │
│  │   ┌─────────────┐      ┌─────────────┐                   │  │
│  │   │ ImageStream │◀─────│  Internal   │                   │  │
│  │   └──────┬──────┘      │  Registry   │                   │  │
│  │          │             └─────────────┘                   │  │
│  │          ▼                                               │  │
│  │   ┌─────────────┐      ┌─────────────┐                   │  │
│  │   │ Deployment  │─────▶│  App Pod(s) │◀──┐               │  │
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
