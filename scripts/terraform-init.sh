#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
region="${AWS_REGION:-us-east-1}"
account="$(aws sts get-caller-identity --query Account --output text)"
bucket="mechanic-shop-tfstate-${account}-${region}"
aws s3api head-bucket --bucket "$bucket" >/dev/null || {
  echo "OS infrastructure must run first (missing shared state bucket $bucket)." >&2
  exit 1
}
terraform -chdir="$repo_dir/terraform" init -input=false \
  -backend-config="bucket=$bucket" -backend-config="key=execution/terraform.tfstate" \
  -backend-config="region=$region" -backend-config="use_lockfile=true"
