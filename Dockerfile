FROM eclipse-temurin:21-jre-alpine

ENV CLOJURE_VERSION=1.12.1.1550

RUN \
apk add --no-cache curl bash make git rlwrap && \
curl -fsSLO https://download.clojure.org/install/posix-install-$CLOJURE_VERSION.sh && \
sha256sum posix-install-$CLOJURE_VERSION.sh && \
    echo "2f5fab4ea5e634939ad5c138c214300c1b3d3587c8ed07e3cf21525fcee7ab28 *posix-install-$CLOJURE_VERSION.sh" | sha256sum -c - && \
chmod +x posix-install-$CLOJURE_VERSION.sh && \
./posix-install-$CLOJURE_VERSION.sh && \
rm posix-install-$CLOJURE_VERSION.sh && \
clojure -e "(clojure-version)" && \
apk del curl

COPY src /app/src
COPY resources /app/resources
COPY config/prod-config.edn /app/config/
COPY deps.edn /app/

WORKDIR /app

CMD exec clojure -X:prod:synkroniser
