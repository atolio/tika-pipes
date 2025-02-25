FROM cgr.dev/chainguard/jdk:latest-dev
COPY libs/ /tika/libs/
COPY plugins/ /tika/plugins/
COPY config/ /tika/config/
COPY bin/ /tika/bin
ARG VERSION='3.0.0-beta1'

USER root

RUN apk update && \
    apk add gdal \
        wget \
	cabextract

EXPOSE 50051
ENV TIKA_PIPES_VERSION=$VERSION
RUN chmod +x "/tika/bin/start-tika-grpc.sh"

RUN chown -R java:java /tika
#ENTRYPOINT ["/tika/bin/start-tika-grpc.sh"]
