terraform {
  required_version = ">= 1.1.7"
  backend "gcs" {
    bucket = "tf-state-madraas-8cec"
    prefix = "job"
  }
}

provider "google" {
  project = "madraas-8cec"
  region = "europe-north1"
  impersonate_service_account = "tf-admin-sa@madraas-8cec.iam.gserviceaccount.com"
}

provider "google-beta" {
  project = "madraas-8cec"
  region = "europe-north1"
  impersonate_service_account = "tf-admin-sa@madraas-8cec.iam.gserviceaccount.com"
}
