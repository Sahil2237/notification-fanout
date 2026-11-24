#!/usr/bin/env python3
"""Run inside the HA tools container against the current primary before the one-time import."""
import json,os,pathlib,psycopg2
from psycopg2 import sql

def main():
    secret=json.loads(pathlib.Path('/config/secrets.json').read_text())
    connection=psycopg2.connect(os.environ['PG_ADMIN_DSN'])
    try:
        connection.autocommit=True
        with connection.cursor() as cursor:
            cursor.execute('SELECT pg_is_in_recovery()');assert not cursor.fetchone()[0]
            for role,key in [('notification_runtime','db_password'),('caddy','caddy_password'),('notification_backup','backup_password')]:
                cursor.execute('SELECT 1 FROM pg_roles WHERE rolname=%s',(role,))
                if not cursor.fetchone():cursor.execute(sql.SQL('CREATE ROLE {} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD {}').format(sql.Identifier(role),sql.Literal(secret[key])))
            cursor.execute('GRANT pg_read_all_data TO notification_backup')
            for database,owner in [('notifications','notification_owner'),('caddy','caddy')]:
                cursor.execute('SELECT 1 FROM pg_database WHERE datname=%s',(database,))
                if not cursor.fetchone():cursor.execute(sql.SQL('CREATE DATABASE {} OWNER {}').format(sql.Identifier(database),sql.Identifier(owner)))
                cursor.execute(sql.SQL('REVOKE CONNECT ON DATABASE {} FROM PUBLIC').format(sql.Identifier(database)))
            cursor.execute('GRANT CONNECT ON DATABASE notifications TO notification_runtime,notification_backup')
    finally:connection.close()
    print('Initialized separate application, backup and TLS-storage roles/databases without rotating existing credentials')
if __name__=='__main__':main()
