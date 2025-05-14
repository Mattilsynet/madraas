prepare-dev:
	gcloud secrets versions access latest --project madraas-8cec --secret "prod-config-secret" > config/prod-secret.txt
	gcloud secrets versions access latest --project madraas-8cec --secret "dev-config-secret" > config/dev-secret.txt

.PHONY: prepare-dev
