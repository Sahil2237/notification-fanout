#!/usr/bin/env python3
"""Create offline purpose-separated CAs and 90-day node/client certificates."""
import argparse,ipaddress,json,os,pathlib,subprocess

def run(*args):subprocess.run(['openssl',*args],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
def issue(ca,directory,name,cn,ip,client_only=False):
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    key=directory/(name+'.key');csr=directory/(name+'.csr');cert=directory/(name+'.crt');ext=directory/(name+'.ext')
    run('genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key))
    run('req','-new','-key',str(key),'-out',str(csr),'-subj','/CN='+cn)
    usage='clientAuth' if client_only else 'serverAuth,clientAuth'
    ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage='+usage+'\nsubjectAltName=IP:'+ip+',IP:127.0.0.1,DNS:localhost\n')
    run('x509','-req','-in',str(csr),'-CA',str(ca.with_suffix('.crt')),'-CAkey',str(ca.with_suffix('.key')),'-CAcreateserial','-out',str(cert),'-days','90','-sha256','-extfile',str(ext))
    csr.unlink();ext.unlink()
def main():
    p=argparse.ArgumentParser();p.add_argument('inventory');p.add_argument('output');p.add_argument('--kafka-password-file');a=p.parse_args();nodes=json.loads(pathlib.Path(a.inventory).read_text())['nodes']
    if len(nodes)!=3 or len({n['private_ip'] for n in nodes})!=3:raise ValueError('Exactly three unique nodes required')
    for node in nodes:
        if not ipaddress.ip_address(node['private_ip']).is_private:raise ValueError('Use private VPC addresses')
    root=pathlib.Path(a.output);root.mkdir(mode=0o700,parents=True,exist_ok=False);ca=root/'offline-ca';ca.mkdir(mode=0o700)
    for purpose in ('control','postgres','kafka'):
        run('req','-x509','-newkey','rsa:3072','-nodes','-keyout',str(ca/(purpose+'.key')),'-out',str(ca/(purpose+'.crt')),'-days','3650','-subj','/CN=notification-'+purpose+'-ca','-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign')
    for node in nodes:
        node_root=root/('node'+str(node['id']))/'tls';ip=node['private_ip']
        for purpose in ('control','postgres','kafka'):
            directory=node_root/purpose;cn=('kafka-' if purpose=='kafka' else 'node')+str(node['id'])
            issue(ca/purpose,directory,purpose,cn,ip)
            (directory/(purpose+'-ca.crt')).write_bytes((ca/(purpose+'.crt')).read_bytes())
        issue(ca/'kafka',node_root/'app','app','notification-service',ip,True)
        for purpose in ('postgres','kafka'):(node_root/'app'/(purpose+'-ca.crt')).write_bytes((ca/(purpose+'.crt')).read_bytes())
    if a.kafka_password_file:
        password=pathlib.Path(a.kafka_password_file).read_text().strip()
        if len(password)<32:raise ValueError('Use a strong PKCS12 password')
        env=os.environ|{'PKCS12_PASSWORD':password}
        for node in nodes:
            directory=root/('node'+str(node['id']))/'tls/kafka'
            subprocess.run(['openssl','pkcs12','-export','-inkey',str(directory/'kafka.key'),'-in',str(directory/'kafka.crt'),'-certfile',str(directory/'kafka-ca.crt'),'-name','kafka-node-'+str(node['id']),'-out',str(directory/'kafka.p12'),'-passout','env:PKCS12_PASSWORD'],env=env,check=True)
            subprocess.run(['keytool','-importcert','-noprompt','-alias','notification-kafka-ca','-file',str(directory/'kafka-ca.crt'),'-keystore',str(directory/'kafka-trust.p12'),'-storetype','PKCS12','-storepass:env','PKCS12_PASSWORD'],env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    print('Created separate control/database/Kafka authorities and three node certificate sets. Offline CA private keys must not be deployed.')
if __name__=='__main__':
    os.umask(0o077);main()
