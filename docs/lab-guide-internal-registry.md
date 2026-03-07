# Lab Guide: Deploying to ROSA using OpenShift Internal Registry

## Overview

This guide walks you through building a container image locally and pushing it directly to the **OpenShift Internal Registry** on your ROSA cluster. This approach gives you full control over the build process without relying on an external registry like Docker Hub or Quay.

**Time to complete:** ~10 minutes

---

## Prerequisites

- ROSA cluster access with `oc` CLI authenticated
- Docker installed locally
- Application source code with a Dockerfile

---

## Key Concepts

### What is the Internal Registry?

OpenShift comes with a built-in container image registry. When you use S2I, OpenShift pushes built images here automatically. You can also push images here manually from your local machine.

**Benefits of the Internal Registry:**
- **No external dependencies:** Images stay entirely within your cluster environment.
- **Tighter security:** No need to configure pull secrets; OpenShift manages access via its own RBAC.
- **Native integration:** Tightly integrated with ImageStreams, triggering automatic deployments when new images are pushed.

### How it Differs from External Registries

| Feature | External Registry (Docker Hub) | Internal Registry |
|---------|--------------------------------|-------------------|
| **Storage** | Hosted externally | Stored on cluster PVs |
| **Authentication** | Separate credentials | Uses `oc whoami` token |
| **Pull Secrets** | Required for private images | Handled automatically |
| **Image URLs** | `docker.io/<user>/<image>` | `<registry-route>/<namespace>/<image>` |

---

## Step-by-Step Deployment

### Step 1: Create a Project

Create the namespace where your app and its image will live:

```bash
oc new-project hello-rosa-internal --description="Internal Registry deployment demo"
```

*Note: In the internal registry, images are scoped to projects. The image path will be `<registry>/hello-rosa-internal/<image-name>`.*

### Step 2: Enable Registry External Access

By default, the internal registry is only accessible from inside the cluster. You must expose it to push images from your local machine:

```bash
# Patch the registry operator to create a default route
oc patch configs.imageregistry.operator.openshift.io/cluster \
  --patch '{"spec":{"defaultRoute":true}}' \
  --type=merge

# Get the registry URL
export REGISTRY=$(oc get route default-route -n openshift-image-registry --template='{{ .spec.host }}')
echo $REGISTRY
```

### Step 3: Authenticate Docker to the Registry

Use your OpenShift token to log in to the registry via Docker:

```bash
docker login -u $(oc whoami) -p $(oc whoami -t) $REGISTRY
```

### Step 4: Build the Image Locally

Build your image.

```bash
docker build --platform linux/amd64 -t hello-rosa:internal .
```

### Step 5: Tag and Push to the Internal Registry

Tag your local image with the registry route and the target namespace:

```bash
# Tag format: <registry-host>/<project-name>/<image-name>:<tag>
docker tag hello-rosa:internal $REGISTRY/hello-rosa-internal/hello-rosa:internal

# Push the image
docker push $REGISTRY/hello-rosa-internal/hello-rosa:internal
```

### Step 6: Deploy the Application

Now that the image is in the internal registry, OpenShift automatically created an `ImageStream` for it. You can deploy it using `oc new-app`:

```bash
oc new-app \
  --image-stream=hello-rosa:internal \
  --name=hello-rosa-internal \
  -e DEPLOYMENT_METHOD=internal
```

*Note: We reference the ImageStream (`hello-rosa:internal`) rather than the full registry URL. OpenShift resolves this internally.*

### Step 7: Expose the Service

Create an HTTPS route so external users can access your app:

```bash
oc create route edge hello-rosa-internal \
  --service=hello-rosa-internal \
  --port=8080
```

### Step 8: Access Your Application

Get the application URL:

```bash
oc get route hello-rosa-internal -o jsonpath='{.spec.host}'
```

Visit the output URL in your browser:
`https://<route-name>-<project>.apps.rosa.<cluster-domain>`

---

## Updating the Application

When you make changes to your code, the update process is streamlined because `oc new-app` linked the deployment to the ImageStream:

1. **Rebuild locally:** `docker build --platform linux/amd64 -t hello-rosa:internal .`
2. **Retag:** `docker tag hello-rosa:internal $REGISTRY/hello-rosa-internal/hello-rosa:internal`
3. **Push:** `docker push $REGISTRY/hello-rosa-internal/hello-rosa:internal`

OpenShift detects the new image digest in the ImageStream and **automatically rolls out the new version** of your application. You don't need to manually restart the deployment!

---

## Troubleshooting

### Docker Login Fails

**Error:** `unauthorized: authentication required`
**Cause:** Your OpenShift token expired.
**Fix:** Log in to OpenShift again (`oc login ...`) and repeat the `docker login` command.

**Error:** `x509: certificate signed by unknown authority`
**Cause:** Your local Docker daemon doesn't trust the registry's certificate.
**Fix:** On ROSA, the default route uses trusted certificates, but if you hit this, ensure your Docker daemon is configured to allow insecure registries or trusts the cluster's CA.

### Push Fails (Access Denied)

**Error:** `denied: requested access to the resource is denied`
**Cause:** You are trying to push to a namespace/project that doesn't exist, or you don't have permission.
**Fix:** Make sure you ran `oc new-project <namespace>` first, and that the tag exactly matches `<registry>/<namespace>/<image>`.

---

## Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                      Developer Workstation                             │
│                                                                        │
│   ┌─────────────┐      ┌─────────────┐         ┌────────────────┐      │
│   │  Source     │─────▶│   Docker    │────────▶│  Docker Push   │      │
│   │  Code +     │      │   Build     │         │ (Using oc      │      │
│   │  Dockerfile │      │ (amd64)     │         │  whoami token) │      │
│   └─────────────┘      └─────────────┘         └───────┬────────┘      │
│                                                        │               │
└────────────────────────────────────────────────────────┼───────────────┘
                                                         │
                                                         ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          ROSA Cluster                                  │
│                                                                        │
│  ┌──────────────────────────────┐    ┌──────────────────────────────┐  │
│  │ Project: openshift-image-    │    │ Project: hello-rosa-internal │  │
│  │          registry            │    │                              │  │
│  │                              │    │       ┌──────────────┐       │  │
│  │  ┌─────────┐   ┌──────────┐  │    │       │ ImageStream  │       │  │
│  │  │ Route   │──▶│ Registry │──┼────┼──────▶│ (Tracks tags │       │  │
│  │  │(Default)│   │  Service │  │    │       │  & digests)  │       │  │
│  │  └─────────┘   └──────────┘  │    │       └──────┬───────┘       │  │
│  └──────────────────────────────┘    │              │               │  │
│                                      │              ▼               │  │
│                                      │       ┌──────────────┐       │  │
│                                      │       │  Deployment  │       │  │
│                                      │       └──────┬───────┘       │  │
│                                      │              │               │  │
│                                      │              ▼               │  │
│                                      │       ┌──────────────┐       │  │
│                                      │       │  App Pod(s)  │◀──┐   │  │
│                                      │       └──────────────┘   │   │  │
│                                      │                          │   │  │
│                                      │       ┌──────────────┐   │   │  │
│                                      │       │   Service    │───┘   │  │
│                                      │       └──────┬───────┘       │  │
│                                      │              │               │  │
│                                      │       ┌──────▼───────┐       │  │
│                                      │       │    Route     │       │  │
│                                      │       │  (edge TLS)  │       │  │
│                                      │       └──────────────┘       │  │
│                                      └──────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
                                                      │
                                                      ▼
                                           ┌─────────────────────┐
                                           │   External Users    │
                                           └─────────────────────┘
```
