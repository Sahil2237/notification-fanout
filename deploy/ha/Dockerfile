ARG GO_IMAGE=golang:1.27.4-bookworm
ARG POSTGRES_IMAGE=postgres:17-bookworm
FROM ${GO_IMAGE} AS privilege-helper
RUN CGO_ENABLED=0 go install github.com/tianon/gosu@29b939aa5bd4dc588af765b8248f6e39150d2565
FROM ${POSTGRES_IMAGE}
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends python3-venv age curl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=privilege-helper /go/bin/gosu /usr/local/bin/gosu
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir --upgrade 'pip==26.3' 'setuptools==84.2.1' 'wheel==0.48.0' && /opt/venv/bin/pip install --no-cache-dir 'patroni[etcd3]==4.1.5' 'psycopg2-binary==2.9.13' 'boto3==1.44.10'
# Packaging is build-only; pip's vendored SBOM contains an obsolete setuptools copy.
RUN /opt/venv/bin/python -m pip uninstall --yes setuptools wheel && /opt/venv/bin/python -m pip uninstall --yes pip \
    && /opt/venv/bin/python -c "import patroni.dcs.etcd3, boto3, psycopg2" \
    && /opt/venv/bin/patroni --version
ENV PATH=/opt/venv/bin:$PATH
COPY postgres-entrypoint.sh /usr/local/bin/ha-postgres
COPY spaces-backup.py /opt/ha/spaces-backup.py
COPY initialize-databases.py /opt/ha/initialize-databases.py
ENTRYPOINT ["bash","/usr/local/bin/ha-postgres"]
