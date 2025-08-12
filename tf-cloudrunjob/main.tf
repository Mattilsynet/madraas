module "cloudrunjob" {
  source = "git@github.com:Mattilsynet/map-tf-cloudrunjob?ref=v0.6.0"

  name              = "madraas-import"
  project_id        = var.project_id
  container_image   = "alpine:latest"
  max_retries       = 1
  task_count        = 1
  ignore_image      = true
  location          = var.region
  env_secret_vars = [
    {
      name = "MADRAAS_CONFIG_SECRET"
      secret_id = google_secret_manager_secret.prod_config_secret.secret_id
    }
  ]
  scheduler = {
    name      = "madraas-import-scheduler"
    schedule  = "0 3 * * *" # Kjør litt utpå natta
  }
  # !! NB: All the secrets mentioned above (mounted or exposed as env variables MUST be listed in accessible_secrets variable)
  accessible_secrets = [google_secret_manager_secret.prod_config_secret.secret_id]
}

resource "google_secret_manager_secret" "prod_config_secret" {
  secret_id = "prod-config-secret"
  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }
}
