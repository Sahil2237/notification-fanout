FROM postgres:17-bookworm
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends python3-venv age curl ca-certificates && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --no-cache-dir 'patroni[etcd3]==4.1.5' 'psycopg2-binary==2.9.13' 'boto3==1.44.10'
ENV PATH=/opt/venv/bin:$PATH
COPY postgres-entrypoint.sh /usr/local/bin/ha-postgres
COPY spaces-backup.py /opt/ha/spaces-backup.py
ENTRYPOINT ["bash","/usr/local/bin/ha-postgres"]
