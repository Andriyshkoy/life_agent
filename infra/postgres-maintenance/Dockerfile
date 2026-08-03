# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM postgres:17.9-bookworm@sha256:47f917f7409eacd22fc5dfb1dee634e1b55cf0c01d1a7eb701be2227a03e0641

ARG AGE_DEBIAN_VERSION=1.1.1-1+b3
ARG JQ_DEBIAN_VERSION=1.6-2.1+deb12u2

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        "age=${AGE_DEBIAN_VERSION}" \
        "jq=${JQ_DEBIAN_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && test "$(age --version)" = "1.1.1" \
    && test "$(jq --version)" = "jq-1.6" \
    && test "$(pg_dump --version)" = \
        "pg_dump (PostgreSQL) 17.9 (Debian 17.9-1.pgdg12+1)" \
    && test "$(pg_restore --version)" = \
        "pg_restore (PostgreSQL) 17.9 (Debian 17.9-1.pgdg12+1)" \
    && groupadd --gid 10001 life-agent-maintenance \
    && useradd \
        --uid 10001 \
        --gid 10001 \
        --no-create-home \
        --shell /usr/sbin/nologin \
        life-agent-maintenance

COPY --chmod=0444 maintenance.conf /opt/life-agent-maintenance/maintenance.conf
COPY --chmod=0555 bin/*.sh /opt/life-agent-maintenance/bin/
COPY --chmod=0444 sql/*.sql /opt/life-agent-maintenance/sql/
RUN chmod 0555 \
    /opt/life-agent-maintenance \
    /opt/life-agent-maintenance/bin \
    /opt/life-agent-maintenance/sql

USER 10001:10001

ENTRYPOINT ["/opt/life-agent-maintenance/bin/entrypoint.sh"]
