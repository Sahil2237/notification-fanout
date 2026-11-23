#!/usr/bin/env python3
"""Render a reviewable App Platform spec. Creates no cloud resources."""
import json,os,pathlib,re,sys
if len(sys.argv)!=3:
    raise SystemExit("Usage: render_spec.py INPUT_JSON OUTPUT_JSON (output contains secrets; keep in .deploy/)")
config=json.loads(pathlib.Path(sys.argv[1]).read_text())
if 'REQUIRED_' in json.dumps(config):raise SystemExit('Replace all REQUIRED_ deployment inputs first')
for key in ('app_name','app_region','vpc_id','postgres_cluster_name','kafka_cluster_name','database_name','registry_name','image_tag','instance_size_slug'):
    if not config.get(key):raise SystemExit('Missing input: '+key)
if not re.fullmatch('[0-9a-f]{40,64}',config['image_tag']):raise SystemExit('image_tag must identify the tested Git commit')
secrets={name:os.environ.get(name,'') for name in ('API_TOKEN','WEBHOOK_SIGNING_SECRET','PG_RUNTIME_USER','PG_RUNTIME_PASSWORD','PG_MIGRATION_USER','PG_MIGRATION_PASSWORD')}
if any(not value for value in secrets.values()):raise SystemExit('Required secret environment variables are missing; see deployment README')
if len(secrets['API_TOKEN'])<32 or len(secrets['WEBHOOK_SIGNING_SECRET'])<32:raise SystemExit('Use at least 32-character API and signing secrets')
if secrets['PG_RUNTIME_USER']==secrets['PG_MIGRATION_USER']:raise SystemExit('Runtime and migration database roles must differ')
for origin in config['approved_webhook_origins']:
    if not origin.startswith('https://'):raise SystemExit('Production webhook origins require HTTPS')
image={'registry_type':'DOCR','registry':config['registry_name'],'repository':'notification-fanout','tag':config['image_tag']}
def env(key,value,secret=False):return {'key':key,'value':value,'scope':'RUN_TIME','type':'SECRET' if secret else 'GENERAL'}
common=[env('DB_URL','jdbc:postgresql://${postgres.HOSTNAME}:${postgres.PORT}/'+config['database_name']),
 env('DB_USER',secrets['PG_RUNTIME_USER'],True),env('DB_PASSWORD',secrets['PG_RUNTIME_PASSWORD'],True),
 env('PG_CA_CERT','${postgres.CA_CERT}',True),env('API_TOKEN',secrets['API_TOKEN'],True),
 env('WEBHOOK_SIGNING_SECRET',secrets['WEBHOOK_SIGNING_SECRET'],True),
 env('WEBHOOK_ORIGINS',json.dumps(config['approved_webhook_origins'])),
 env('KAFKA_BOOTSTRAP_SERVERS','${events.HOSTNAME}:${events.PORT}'),env('KAFKA_USERNAME','${events.USERNAME}',True),
 env('KAFKA_PASSWORD','${events.PASSWORD}',True),env('KAFKA_CA_CERT','${events.CA_CERT}',True)]
api={'name':'api','image':image,'run_command':'java -jar /opt/app/notification-fanout.jar server /opt/app/production.yml',
 'http_port':8080,'instance_count':config['api_instances'],'instance_size_slug':config['instance_size_slug'],
 'health_check':{'http_path':'/health/ready','initial_delay_seconds':15,'period_seconds':10,'timeout_seconds':5},
 'liveness_health_check':{'http_path':'/health/live','initial_delay_seconds':30,'period_seconds':10,'timeout_seconds':5},
 'termination':{'drain_seconds':15,'grace_period_seconds':45}}
worker={'name':'delivery-worker','image':image,'run_command':api['run_command'],
 'instance_count':config['worker_instances'],'instance_size_slug':config['instance_size_slug'],
 'envs':[env('WORKER_ENABLED','true'),env('KAFKA_PUBLISHER_ENABLED','true'),env('KAFKA_CONSUMER_ENABLED','true')],
 'termination':{'grace_period_seconds':45}}
migration={'name':'migrate','image':image,'kind':'PRE_DEPLOY',
 'run_command':'java -jar /opt/app/notification-fanout.jar db migrate /opt/app/production.yml',
 'instance_count':1,'instance_size_slug':config['instance_size_slug'],
 'envs':[env('DB_USER',secrets['PG_MIGRATION_USER'],True),env('DB_PASSWORD',secrets['PG_MIGRATION_PASSWORD'],True)]}
spec={'name':config['app_name'],'region':config['app_region'],'vpc':{'id':config['vpc_id']},
 'databases':[{'name':'postgres','engine':'PG','production':True,'cluster_name':config['postgres_cluster_name'],'db_name':config['database_name']},
              {'name':'events','engine':'KAFKA','production':True,'cluster_name':config['kafka_cluster_name']}],
 'envs':common,'services':[api],'workers':[worker],'jobs':[migration],
 'ingress':{'rules':[{'component':{'name':'api'},'match':{'path':{'prefix':'/'}}}]},
 'alerts':[{'rule':'DEPLOYMENT_FAILED'},{'rule':'DOMAIN_FAILED'}]}
out=pathlib.Path(sys.argv[2]);out.parent.mkdir(parents=True,exist_ok=True)
fd=os.open(out,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as stream:json.dump(spec,stream,indent=2)
print('Rendered App Platform spec to '+str(out)+'; validate and review before deployment. No resources created.')
