terraform {
  required_version = ">= 1.10.0"
  backend "s3" {}
  required_providers {
    aws          = { source = "hashicorp/aws", version = "~> 6.0" }
    mongodbatlas = { source = "mongodb/mongodbatlas", version = "~> 2.0" }
  }
}
provider "aws" { region = var.aws_region }
provider "mongodbatlas" {
  public_key  = var.atlas_public_key
  private_key = var.atlas_private_key
}
variable "aws_region" {
  type    = string
  default = "us-east-1"
  validation {
    condition     = contains(["us-east-1", "us-west-2"], var.aws_region)
    error_message = "Learner Lab permits us-east-1 or us-west-2 only."
  }
}
variable "cluster_name" {
  type    = string
  default = "mechanic-shop-academy"
}
variable "atlas_org_id" { type = string }
variable "atlas_public_key" {
  type      = string
  sensitive = true
}
variable "atlas_private_key" {
  type      = string
  sensitive = true
}
variable "db_username" {
  type    = string
  default = "executionapp"
}
variable "db_password" {
  type      = string
  sensitive = true
  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{16,}$", var.db_password))
    error_message = "Use at least 16 URL-safe characters (letters, digits, _ or -)."
  }
}
variable "atlas_access_cidr" {
  type    = string
  default = "0.0.0.0/0"
}

# Read-only discovery: execution never provisions or claims the OS cluster.
data "aws_eks_cluster" "shared" { name = var.cluster_name }

resource "mongodbatlas_project" "execution" {
  name   = "mechanic-shop-execution"
  org_id = var.atlas_org_id
}
resource "mongodbatlas_advanced_cluster" "execution" {
  project_id   = mongodbatlas_project.execution.id
  name         = "execution-m0"
  cluster_type = "REPLICASET"
  replication_specs = [{
    region_configs = [{
      electable_specs       = { instance_size = "M0" }
      provider_name         = "TENANT"
      backing_provider_name = "AWS"
      region_name           = upper(replace(var.aws_region, "-", "_"))
      priority              = 7
    }]
  }]
  lifecycle { prevent_destroy = true }
}
resource "mongodbatlas_database_user" "execution" {
  project_id         = mongodbatlas_project.execution.id
  auth_database_name = "admin"
  username           = var.db_username
  password           = var.db_password
  roles {
    role_name     = "readWrite"
    database_name = "execution-db"
  }
}
resource "mongodbatlas_project_ip_access_list" "eks_egress" {
  project_id = mongodbatlas_project.execution.id
  cidr_block = var.atlas_access_cidr
  comment    = "Academy EKS public node egress (restrict when stable egress is available)"
}
output "mongodb_uri" {
  value     = "${replace(mongodbatlas_advanced_cluster.execution.connection_strings.standard_srv, "mongodb+srv://", "mongodb+srv://${var.db_username}:${var.db_password}@")}/execution-db?retryWrites=true&w=majority"
  sensitive = true
}
