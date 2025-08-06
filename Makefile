VERSION := $(shell git rev-parse --short=10 HEAD)
IMAGE = europe-north1-docker.pkg.dev/artifacts-352708/mat/madraas-import:$(VERSION)

prepare-dev:
	gcloud secrets versions access latest --project madraas-8cec --secret "prod-config-secret" > config/prod-secret.txt
	gcloud secrets versions access latest --project madraas-8cec --secret "dev-config-secret" > config/dev-secret.txt

# Run unit test suite
test:
	clojure -M:dev -m kaocha.runner unit

docker: Dockerfile
	docker build -f Dockerfile -t $(IMAGE) .

publish:
	docker push $(IMAGE)

.PHONY: prepare-dev test publish
