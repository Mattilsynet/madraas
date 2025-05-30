prepare-dev:
	gcloud secrets versions access latest --project madraas-8cec --secret "prod-config-secret" > config/prod-secret.txt
	gcloud secrets versions access latest --project madraas-8cec --secret "dev-config-secret" > config/dev-secret.txt

# Run unit test suite
test:
	clojure -M:dev -m kaocha.runner unit

.PHONY: prepare-dev test
