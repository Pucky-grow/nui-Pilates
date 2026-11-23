terraform {
  required_version = ">= 1.8.0, < 2.0.0"
  required_providers {
    alicloud = { source = "aliyun/alicloud", version = "2.0.0" }
  }
}
# Authentication comes from an operator-selected role/credential chain, never from HCL.
provider "alicloud" { region = var.region }
resource "alicloud_vpc" "staging" {
  vpc_name   = var.name
  cidr_block = var.vpc_cidr
}
resource "alicloud_vswitch" "staging" {
  vpc_id       = alicloud_vpc.staging.id
  zone_id      = var.zone_id
  cidr_block   = var.subnet_cidr
  vswitch_name = var.name
}
resource "alicloud_security_group" "app" {
  security_group_name = var.name
  vpc_id              = alicloud_vpc.staging.id
}
resource "alicloud_security_group_rule" "https" {
  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "443/443"
  priority          = 1
  security_group_id = alicloud_security_group.app.id
  cidr_ip           = var.allowed_test_cidr
}
resource "alicloud_security_group_rule" "operations" {
  type              = "ingress"
  ip_protocol       = "tcp"
  nic_type          = "intranet"
  policy            = "accept"
  port_range        = "22/22"
  priority          = 1
  security_group_id = alicloud_security_group.app.id
  cidr_ip           = var.operations_cidr
}
resource "alicloud_ram_role" "app" {
  role_name                   = var.name
  assume_role_policy_document = jsonencode({ Version = "1", Statement = [{ Action = "sts:AssumeRole", Effect = "Allow", Principal = { Service = ["ecs.aliyuncs.com"] } }] })
}
resource "alicloud_oss_bucket" "files" {
  bucket = var.bucket_name
  versioning { status = "Enabled" }
  server_side_encryption_rule { sse_algorithm = "AES256" }
  lifecycle { prevent_destroy = true }
}
resource "alicloud_ram_policy" "files" {
  policy_name     = var.name
  policy_document = jsonencode({ Version = "1", Statement = [{ Effect = "Allow", Action = ["oss:GetObject", "oss:PutObject"], Resource = ["acs:oss:*:*:${alicloud_oss_bucket.files.bucket}/objects/*"] }] })
}
resource "alicloud_ram_role_policy_attachment" "files" {
  role_name   = alicloud_ram_role.app.role_name
  policy_name = alicloud_ram_policy.files.policy_name
  policy_type = "Custom"
}
resource "alicloud_instance" "app" {
  instance_name              = var.name
  instance_type              = var.ecs_instance_type
  image_id                   = var.ecs_image_id
  security_groups            = [alicloud_security_group.app.id]
  vswitch_id                 = alicloud_vswitch.staging.id
  key_name                   = var.existing_ssh_key_name
  system_disk_category       = "cloud_essd"
  system_disk_size           = 40
  system_disk_encrypted      = true
  internet_max_bandwidth_out = var.internet_bandwidth_mbps
  instance_charge_type       = "PostPaid"
  internet_charge_type       = "PayByTraffic"
  deletion_protection        = true
  lifecycle { prevent_destroy = true }
}
resource "alicloud_db_instance" "mysql" {
  engine                   = "MySQL"
  engine_version           = var.rds_engine_version
  instance_type            = var.rds_instance_type
  instance_storage         = var.rds_storage_gb
  instance_charge_type     = "Postpaid"
  vswitch_id               = alicloud_vswitch.staging.id
  security_ips             = [alicloud_instance.app.private_ip]
  db_instance_storage_type = "cloud_essd"
  instance_name            = var.name
  monitoring_period        = 60
  parameters {
    name  = "character_set_server"
    value = "utf8mb4"
  }
  parameters {
    name  = "collation_server"
    value = "utf8mb4_general_ci"
  }
  parameters {
    name  = "time_zone"
    value = "+08:00"
  }
  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = var.rds_lifecycle_approved
      error_message = "Confirm a supported RDS kernel/maintenance policy and replay tests before resource creation."
    }
  }
}
resource "alicloud_kvstore_instance" "redis" {
  instance_name  = var.name
  instance_class = var.redis_instance_class
  engine_version = var.redis_engine_version
  instance_type  = "Redis"
  vswitch_id     = alicloud_vswitch.staging.id
  security_ips   = [alicloud_instance.app.private_ip]
  ssl_enable     = "Enable"
  payment_type   = "PostPaid"
  # Password/ACL must be configured through a protected platform channel before connecting.
  lifecycle { prevent_destroy = true }
}
output "resource_ids" {
  value = { ecs = alicloud_instance.app.id, rds = alicloud_db_instance.mysql.id, tair = alicloud_kvstore_instance.redis.id, bucket = alicloud_oss_bucket.files.bucket, vpc = alicloud_vpc.staging.id, vswitch = alicloud_vswitch.staging.id, role = alicloud_ram_role.app.role_name }
}
output "private_endpoints" {
  value = { ecs = alicloud_instance.app.private_ip, rds = alicloud_db_instance.mysql.connection_string, tair = alicloud_kvstore_instance.redis.connection_domain }
}

resource "alicloud_ecs_ram_role_attachment" "app" {
  ram_role_name = alicloud_ram_role.app.role_name
  instance_id   = alicloud_instance.app.id
}
resource "alicloud_oss_bucket_acl" "private" {
  bucket = alicloud_oss_bucket.files.bucket
  acl    = "private"
}
resource "alicloud_oss_bucket_public_access_block" "private" {
  bucket              = alicloud_oss_bucket.files.bucket
  block_public_access = true
}
