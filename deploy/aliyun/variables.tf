variable "name" {
  type        = string
  description = "Isolated test resource name"
}
variable "region" {
  type        = string
  description = "Authorized Aliyun region"
}
variable "zone_id" {
  type        = string
  description = "Authorized zone available to ECS/RDS/Tair"
}
variable "vpc_cidr" {
  type        = string
  description = "VPC CIDR without existing network overlap"
}
variable "subnet_cidr" {
  type        = string
  description = "Subnet CIDR"
}
variable "allowed_test_cidr" {
  type        = string
  description = "Approved HTTPS source CIDR; no automatic public access"
}
variable "operations_cidr" {
  type        = string
  description = "Approved operations source CIDR"
}
variable "bucket_name" {
  type        = string
  description = "Globally unique private test bucket"
}
variable "ecs_instance_type" {
  type        = string
  description = "Quoted test ECS SKU"
}
variable "ecs_image_id" {
  type        = string
  description = "Approved Linux image ID and architecture"
}
variable "existing_ssh_key_name" {
  type        = string
  description = "Existing public SSH key reference"
}
variable "internet_bandwidth_mbps" {
  type        = number
  description = "Approved outbound bandwidth; requires budget approval"
}
variable "rds_engine_version" {
  type        = string
  description = "Supported RDS MySQL engine; exact kernel checked after provisioning"
}
variable "rds_lifecycle_approved" {
  type        = bool
  description = "Confirmed maintenance lifecycle and target tests; false blocks creation"
}
variable "rds_instance_type" {
  type        = string
  description = "Quoted RDS SKU"
}
variable "rds_storage_gb" {
  type        = number
  description = "Approved RDS storage GB"
}
variable "redis_instance_class" {
  type        = string
  description = "Quoted compatible Redis/Tair SKU"
}
variable "redis_engine_version" {
  type        = string
  description = "Supported target Redis engine version"
}
