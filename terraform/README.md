# Execution infrastructure: MongoDB Atlas M0

This stack creates a **free-tier M0** MongoDB Atlas replica set, a project, the `executionapp` read/write database user for `execution-db`, and an IP access-list entry. You do **not** need to create a MongoDB user manually: the Atlas public/private keys authorize Terraform to manage the project; `MONGODB_PASSWORD` becomes the application's database-user password. The pipeline builds and injects `MONGODB_URI`. It only **reads** the OS-owned EKS cluster. Execution's application declares its own `execution.commands` queue, dead-letter queue and bindings on the OS-owned RabbitMQ exchanges, and its pipeline deploys only Execution configuration, secret, Service, Deployment and HPA.

## One-time Atlas setup

1. Create a free MongoDB Atlas account and organization at [cloud.mongodb.com](https://cloud.mongodb.com/). Copy its **Organization ID** from Organization Settings to the GitHub Actions variable `ATLAS_ORG_ID` in Execution.
2. In **Organization Access Manager → API Keys**, create an organization API key with permission to create projects (Organization Project Creator or higher), deploy clusters and administer project database users/network access. Copy its public/private keys once to GitHub Actions secrets `ATLAS_PUBLIC_KEY` and `ATLAS_PRIVATE_KEY` in Execution. If Atlas requires an API access list for the key, allow GitHub-hosted runner egress or use an accessible runner.
3. Set GitHub Actions secret `MONGODB_PASSWORD` to a new random **16+ character URL-safe** password (`A-Z`, `a-z`, `0-9`, `_`, `-`). Terraform creates the database user; its URI is generated as a sensitive output and injected into the Execution Kubernetes Secret by the pipeline.

M0 is selected explicitly; this configuration cannot silently upgrade to a paid tier. Atlas account limits may allow only one free M0 per project/organization. The default database IP access list is `0.0.0.0/0` because lab EKS public nodes have changing outbound IPs; authentication and TLS still apply. If you have a stable EKS egress CIDR, set `TF_VAR_atlas_access_cidr` to that CIDR and narrow the list. MongoDB Atlas is external to the AWS Academy budget; check Atlas's current M0 free-tier terms when creating the account.

**GitHub Actions secrets:** `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (temporary lab AWS Details); `DOCKERHUB_TOKEN`; `JWT_SECRET`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` (same as OS); `ATLAS_PUBLIC_KEY`, `ATLAS_PRIVATE_KEY`, `MONGODB_PASSWORD`. Optional: `NEW_RELIC_LICENSE_KEY`, `SONAR_TOKEN`.

**GitHub Actions variables:** `DOCKERHUB_USERNAME`, `ATLAS_ORG_ID`; optional `AWS_REGION` (`us-east-1` default), `EKS_CLUSTER_NAME` (`mechanic-shop-academy` default), `NEW_RELIC_APP_NAME`, `SONAR_PROJECT_KEY`, `SONAR_ORGANIZATION`, `SONAR_HOST_URL`.

First deploy OS successfully. On push, the pipeline checks active AWS credentials and the shared cluster, initializes its separate `execution/terraform.tfstate` in the OS-created S3 bucket, applies Atlas M0, and deploys Execution. It checks that OS RabbitMQ/Kong exist. To manually inspect a plan, export `TF_VAR_atlas_org_id`, `TF_VAR_atlas_public_key`, `TF_VAR_atlas_private_key`, `TF_VAR_db_password`, `TF_VAR_aws_region` and `AWS_REGION`, then run `bash scripts/terraform-init.sh` and `terraform -chdir=terraform plan`.

An existing manually created Atlas project or cluster is **not** automatically taken over: import it into Execution's state before applying, or use a fresh organization. The cluster has `prevent_destroy = true` to prevent accidental loss. See OS `terraform/README.md` for shared state and deploy order.
