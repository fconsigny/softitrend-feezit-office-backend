#!/usr/bin/env python3
"""Real Office + Feezit jars + React/Chromium; synthetic, loopback-only data.
No production access, provider email, or external HTTP. Requires built jars and
frontend dependencies. All child servers stopped and disposable H2 removed.
"""
import datetime as dt
import base64
import contextlib
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

ROOT=Path(__file__).resolve().parents[1]
FEEZIT=ROOT.parent/'softitrend-feezit-backend'
FRONTEND=ROOT.parent/'softitrend-feezit-office-frontend'
JAVA=Path(os.environ.get('FEEZIT_JAVA_HOME',str(Path.home()/'.local/jdk-25.0.2')))/'bin/java'
BUN=str(Path.home()/'.local/bin/bun')
SCRATCH=Path(os.environ.get('TMPDIR',str(Path.home()/'.hermes/cache/scratch')))
MANAGEMENT='synthetic-smoke-management-secret'
HASH='$2b$04$.Y2ZtUVqsvGorQWKEXT3S.iD/USZkKLPlnqbWmfdJkNXbm9bRV9T2'
PASSWORD='synthetic-smoke-password-only'

class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self, req, fp, code, msg, headers, newurl): return None

def main():
 SCRATCH.mkdir(parents=True,exist_ok=True)
 evidence=Path(tempfile.mkdtemp(prefix='office-integration-evidence-',dir=SCRATCH))
 checks=[]; processes=[]; logs=[]
 def available(port):
  with socket.socket() as s:
   s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
   s.bind(('127.0.0.1',port))
 for p in (8091,3011): available(p)
 with socket.socket() as s:
  s.bind(('127.0.0.1',0)); feeport=s.getsockname()[1]
 fee=f'http://127.0.0.1:{feeport}'
 with socket.socket() as s:
  s.bind(('127.0.0.1',0)); imageport=s.getsockname()[1]
 image=f'http://127.0.0.1:{imageport}/article.png'
 env={k:v for k,v in os.environ.items() if not k.startswith(('OFFICE_','FEEZIT_','SPRING_','SERVER_')) and k not in ('PORT','JAVA_TOOL_OPTIONS')}
 clean=urllib.request.build_opener(NoRedirect)
 cookies=http.cookiejar.CookieJar()
 office=urllib.request.build_opener(NoRedirect,urllib.request.HTTPCookieProcessor(cookies))
 def request(base,method,path,expected=200,body=None,token=None,management=False,csrf=None,client=clean,form=False) -> Any:
  headers={'Accept':'application/json'}
  if token: headers['Authorization']='Bearer '+token
  if management: headers['X-Management-Secret']=MANAGEMENT
  if csrf: headers[csrf['headerName']]=csrf['token']
  data=None
  if body is not None:
   data=urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()
   headers['Content-Type']='application/x-www-form-urlencoded' if form else 'application/json'
  try: response=client.open(urllib.request.Request(base+path,data=data,headers=headers,method=method),timeout=15)
  except urllib.error.HTTPError as error: response=error
  with response:
   raw=response.read()
   if response.status!=expected: raise AssertionError(f'{method} {path}: expected {expected}, got {response.status}: {raw.decode()[:500]}')
   checks.append({'server':'office' if base.endswith(':8091') else 'feezit','method':method,'path':path,'status':response.status})
   return json.loads(raw) if raw and response.headers.get_content_type()=='application/json' else None
 def start(command,cwd,childenv,name,probe):
  out=(evidence/(name+'.log')).open('wb'); logs.append(out)
  process=subprocess.Popen(command,cwd=cwd,env=childenv,stdout=out,stderr=subprocess.STDOUT); processes.append(process)
  deadline=time.monotonic()+70
  while True:
   if process.poll() is not None: raise RuntimeError(f'{name} exited: see {evidence}')
   try:
    with urllib.request.urlopen(probe,timeout=2) as r:
     r.read(); return
   except (urllib.error.URLError,OSError):
    if time.monotonic()>deadline: raise RuntimeError(f'{name} timeout: see {evidence}')
    time.sleep(.2)
 database=tempfile.TemporaryDirectory(prefix='office-integration-db-',dir=SCRATCH)
 try:
  with contextlib.nullcontext(database.name) as dbdir:
   (evidence/'article.png').write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII='))
   start(['python3','-m','http.server',str(imageport),'--bind','127.0.0.1','--directory',str(evidence)],ROOT,env,'synthetic-image',image)
   feenv=env|{'PORT':str(feeport),'SPRING_PROFILES_ACTIVE':'dev','FEEZIT_R2DBC_URL':f'r2dbc:h2:file:///{dbdir}/db?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE','FEEZIT_JDBC_URL':f'jdbc:h2:file:{dbdir}/db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE','FEEZIT_DB_USER':'sa','FEEZIT_DB_PASSWORD':'','FEEZIT_MANAGEMENT_SECRET':MANAGEMENT,'FEEZIT_JOBS_ENABLED':'false','FEEZIT_EMAIL_ENABLED':'false','FEEZIT_AUTH_PER_MINUTE':'1000'}
   start([str(JAVA),'--enable-native-access=ALL-UNNAMED','-Xmx384m','-jar',str(FEEZIT/'target/feezit-backend-0.1.0-SNAPSHOT.jar'),'--server.address=127.0.0.1'],FEEZIT,feenv,'feezit',fee+'/health')
   identities={}
   for username in ('officemain','officewait','officeban','officeui'):
    identities[username]=request(fee,'POST','/api/auth/register',201,{'username':username,'email':username+'@example.test','password':'Synthetic-creator-password-123','ageConfirmed':True})
   offenv=env|{'OFFICE_OPERATOR_USERNAME':'operator-smoke','OFFICE_OPERATOR_PASSWORD_HASH':HASH,'FEEZIT_API_BASE_URL':fee,'FEEZIT_MANAGEMENT_SECRET':MANAGEMENT}
   base='http://127.0.0.1:8091'
   start([str(JAVA),'--enable-native-access=ALL-UNNAMED','-Xmx256m','-jar',str(ROOT/'target/feezit-office-backend-0.1.0-SNAPSHOT.jar'),'--spring.profiles.active=test'],ROOT,offenv,'office',base+'/office-api/csrf')
   def o(method,path,expected=200,body=None,csrf=None):
    return request(base,method,'/office-api/'+path,expected,body,csrf=csrf,client=office)
   o('GET','session',401)
   csrf=o('GET','csrf')
   request(base,'POST','/office-api/auth/login',403,{'username':'operator-smoke','password':PASSWORD},client=office,form=True)
   request(base,'POST','/office-api/auth/login',204,{'username':'operator-smoke','password':PASSWORD},csrf=csrf,client=office,form=True)
   assert o('GET','session')=={'username':'operator-smoke'}
   csrf=o('GET','csrf')
   def m(method,path,expected=200,body=None): return o(method,'management/'+path,expected,body,csrf if method!='GET' else None)
   for path in ('accounts','promos','incidents'):
    request(fee,'GET','/api/management/'+path,401,token=identities['officemain']['token'])
   accounts=m('GET','accounts?page=0&size=20'); assert accounts['total']==4
   assert all(set(r)=={'id','username','name','banned','visibility'} for r in accounts['items'])
   now=dt.datetime.now(dt.timezone.utc)-dt.timedelta(minutes=2)
   owner=identities['officemain']['userId']; wait=identities['officewait']['userId']; ban=identities['officeban']['userId']
   m('PUT','brands/aliases',204,{'alias':'Isolated spelling','canonicalStore':'Isolated brand'})
   assert any(r['ALIAS_KEY']=='isolated spelling' for r in m('GET','brands'))
   m('POST','waitlist',204,{'ownerId':wait,'store':'Isolated spelling','creatorName':'Waiting synthetic','code':'wait20'})
   assert m('GET','waitlist?store=Isolated%20brand')[0]['OWNER_ID']==wait
   m('POST','waitlist/responses',204,{'ownerId':wait,'store':'Isolated spelling','receivedAt':now.isoformat(),'emailReference':'synthetic-email-response'})
   assert m('GET','waitlist/responses?store=Isolated%20brand')[0]['OWNER_ID']==wait
   reservation={'ownerId':owner,'store':'Isolated brand','code':'mIxEd20','articleName':'Article isolated','category':'Test','destinationUrl':'https://example.com/isolated','agreementReceivedAt':now.isoformat(),'agreementReference':'synthetic-agreement','documentVersion':'0.1-test','articleDescription':'Description initiale opérateur','articleImage':image}
   draft=m('POST','reservations',201,reservation); pid=draft['id']
   assert draft['publicationStatus']=='draft' and draft['endDate'] is None and draft['code']=='mIxEd20'
   assert draft['articleDescription']==reservation['articleDescription'] and draft['articleImage']==image
   detail=m('GET',f'promos/{pid}'); assert detail==draft|{'status':'upcoming'}
   request(base,'GET',f'/office-api/management/promos/{pid}',401)
   request(base,'PUT',f'/office-api/management/promos/{pid}/content',403,{'articleDescription':'No CSRF','articleImage':image},client=office)
   request(fee,'PUT',f'/api/management/promos/{pid}/content',401,{'articleDescription':'Creator cannot manage','articleImage':image},token=identities['officemain']['token'])
   edited=m('PUT',f'promos/{pid}/content',200,{'articleDescription':'Description opérateur modifiée','articleImage':image+'?edited=1'})
   assert edited['articleDescription']=='Description opérateur modifiée' and edited['articleImage']==image+'?edited=1'
   assert all(edited[k]==draft[k] for k in ('code','store','ownerId','startDate','endDate','publicationStatus'))
   assert m('GET',f'promos/{pid}')==edited
   assert request(fee,'GET','/api/me/promos',token=identities['officemain']['token'])['items'][0]==edited
   m('PUT',f'promos/{pid}/content',400,{'articleDescription':'Bad URL','articleImage':'javascript:alert(1)'})
   m('PUT',f'promos/{pid}/content',400,{'articleImage':image})
   assert m('GET',f'promos/{pid}')==edited
   assert m('GET','promos?page=0&size=20')['items'][0]['id']==pid
   request(fee,'GET','/api/promos/'+pid,404)
   m('POST','reservations',409,reservation|{'ownerId':wait})
   publication={'articleName':'Article isolated','articleImage':'','articleDescription':'Description réellement enregistrée dans test','category':'Test','conditions':'20% test','code':'mIxEd20','startDate':now.date().isoformat(),'endDate':(now.date()+dt.timedelta(days=30)).isoformat(),'store':'Isolated brand','destinationUrl':'https://example.com/isolated'}
   real=request(fee,'PUT','/api/me/promos/'+pid,200,publication,token=identities['officemain']['token']); assert real['publicationStatus']=='published'
   updated=m('PUT',f'promos/{pid}/content',200,{'articleDescription':'Contenu public mis à jour','articleImage':image})
   public=request(fee,'GET','/api/promos/'+pid); assert public['articleDescription']==updated['articleDescription'] and public['articleImage']==image
   request(fee,'POST','/api/creators/officemain/views',204)
   request(fee,'GET','/api/r/promos/'+pid,302)
   stats=m('GET',f'accounts/{owner}/stats'); assert stats['totals']['profile_view']==1 and stats['totals']['promo_outbound']==1 and stats['revenue'] is None
   m('POST',f'promos/{pid}/reports',202,{'reporterEmail':'synthetic-reporter@example.test'})
   issues=m('GET','incidents?page=0&size=20')['items']; assert issues[0]['promoId']==pid and issues[0]['kind'] is None and issues[0]['openedAt'] is None
   outbox=m('GET','outbox'); assert outbox and all(r['DELIVERY_STATUS']=='pending' and r['SENT_AT'] is None for r in outbox)
   assert m('GET','email-deliveries')
   # Test-only attestation fixture. Provider is disabled: no real email sent.
   notification=next(r for r in outbox if r['KIND']=='invalid_first')
   attestation={'sentAt':dt.datetime.now(dt.timezone.utc).isoformat(),'deliveryReference':'synthetic-no-email-test-attestation'}
   # Current Feezit guard refuses all manual confirmations, even email disabled.
   m('PUT',f"outbox/{notification['ID']}/sent",409,attestation)
   persisted=next(r for r in m('GET','outbox') if r['ID']==notification['ID'])
   assert persisted['DELIVERY_STATUS']=='pending' and persisted['SENT_AT'] is None
   assert m('GET','incidents')['items'][0]['firstSentAt'] is None
   m('PUT',f'promos/{pid}/resolve',204)
   assert m('GET','incidents')['total']==0
   decision={'reason':'Synthetic test decision','facts':'Test only facts','exchanges':'Test only exchanges','dossierReference':'synthetic-dossier'}
   m('PUT',f'accounts/{owner}/moderation',204,decision)
   request(fee,'GET','/api/creators/officemain',404)
   assert m('GET','accounts?q=officemain')['items'][0]['visibility']=='masked'
   assert m('GET',f'accounts/{owner}/moderation')[0]['REASON']==decision['reason']
   outbox_before=m('GET','outbox')
   masked=m('GET',f'promos/{pid}')
   changed=m('PUT',f'promos/{pid}/content',200,{'articleDescription':'Contenu sous modération','articleImage':image+'?masked=1'})
   assert changed['publicationStatus']==masked['publicationStatus']
   assert m('GET',f'accounts/{owner}/moderation')[0]['REASON']==decision['reason']
   assert m('GET','outbox')==outbox_before
   request(fee,'GET','/api/promos/'+pid,404)
   m('DELETE',f'accounts/{owner}/moderation',204)
   request(fee,'GET','/api/creators/officemain',200)
   m('POST',f'promos/{pid}/fraud-removal',400,{'reason':'synthetic','dossierReference':'synthetic','fraudEstablished':False})
   assert m('GET','promos')['total']==1
   m('POST',f'promos/{pid}/fraud-removal',204,{'reason':'synthetic','dossierReference':'synthetic','fraudEstablished':True})
   assert m('GET','promos')['total']==0
   request(fee,'GET','/api/me/profile',200,token=identities['officemain']['token'])
   assert m('GET',f'accounts/{owner}/stats')['totals']['promo_outbound']==1
   assert any(r['KIND']=='brand_available' and r['DELIVERY_STATUS']=='pending' for r in m('GET','outbox'))
   m('PUT',f'accounts/{ban}/ban',204,decision)
   banned=m('GET','accounts?q='+ban)['items'][0]; assert banned['banned'] and banned['visibility']=='absent'
   request(fee,'GET','/api/me/profile',401,token=identities['officeban']['token'])
   m('DELETE',f'accounts/{ban}/ban',204)
   assert m('GET','accounts?q='+ban)['items'][0]['visibility']=='absent'
   m('POST',f'accounts/{ban}/profile',201,{'username':'officebannew','name':'New synthetic profile','bio':'','avatar':'','category':'Test'})
   assert m('GET','accounts?q='+ban)['items'][0]['username']=='officebannew'
   m('POST','jobs/run',204)
   o('POST','auth/logout',204,csrf=csrf); o('GET','management/accounts',401)
   ui_fixture={'uiOwnerId':identities['officeui']['userId'],'statsOwnerId':owner,'agreementTime':now.strftime('%Y-%m-%dT%H:%M'),'imageUrl':image}
   fixture=evidence/'browser-fixture.json'; fixture.write_text(json.dumps(ui_fixture))
   browserenv=env|{'OFFICE_INTEGRATION_FIXTURE':str(fixture),'OFFICE_INTEGRATION_SCREENSHOT':str(evidence/'real-servers-synthetic.png')}
   start([BUN,'--bun','run','dev'],FRONTEND,browserenv,'frontend','http://127.0.0.1:3011')
   with (evidence/'chromium.log').open('wb') as out:
    result=subprocess.run([BUN,'--bun','x','playwright','test','--config','playwright.integration.config.js'],cwd=FRONTEND,env=browserenv,stdout=out,stderr=subprocess.STDOUT,timeout=180)
   if result.returncode: raise AssertionError(f'Chromium integration failed: {evidence}/chromium.log')
   # Read back exact owner in Feezit after browser mutation, not only a UI toast.
   own=request(fee,'GET','/api/me/promos',token=identities['officeui']['token']); assert len(own['items'])==1 and own['items'][0]['code']=='mIxEd-ui20' and own['items'][0]['publicationStatus']=='draft'
   assert own['items'][0]['articleDescription']=='Description UI modifiée puis relue'
   assert own['items'][0]['articleImage']==image+'?ui=edited'
   summary={'result':'PASS','httpChecks':len(checks),'browserScenarios':1,'realServers':['Feezit jar dev/H2 disposable','Office jar test','React Vite','Chromium'],'emailProvider':'disabled; no email sent','credentials':'synthetic only','evidence':str(evidence),'checks':checks}
   (evidence/'result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
   print(json.dumps({k:v for k,v in summary.items() if k!='checks'},ensure_ascii=False))
 finally:
  for process in reversed(processes):
   if process.poll() is None:
    process.terminate()
    try: process.wait(timeout=12)
    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
  for log in logs: log.close()
  database.cleanup()
  print('All spawned servers stopped; disposable test database removed. Evidence:',evidence)

if __name__=='__main__': main()
