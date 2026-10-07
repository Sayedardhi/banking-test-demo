#!/usr/bin/env python3
"""Local evidence collector and read-only testing dashboard (Python standard library)."""
import argparse, datetime, hashlib, json, os, shutil, subprocess, sys, signal, time, uuid
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
HOME = Path(__file__).resolve().parent
DATA = ROOT / '.local/test-observability'
LAYERS = ('unit', 'integration', 'e2e')

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()
def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.tmp'); tmp.write_text(json.dumps(value, indent=2)); tmp.replace(path)
def config(): return json.loads((HOME / 'config.json').read_text())
def fingerprint():
    # Capture tracked and untracked source, excluding generated runtime/report files.
    names = subprocess.check_output(['git','ls-files','-co','--exclude-standard','-z'], cwd=ROOT).split(b'\0')
    digest = hashlib.sha256()
    for name in sorted(set(n for n in names if n)):
        p = ROOT / os.fsdecode(name)
        digest.update(name)
        if p.is_file(): digest.update(p.read_bytes())
    return digest.hexdigest()
def junit(paths):
    cases=[]
    for path in paths:
        root=ET.parse(path).getroot()
        for case in root.iter('testcase'):
            failure=case.find('failure'); error=case.find('error')
            problem=failure if failure is not None else error
            cases.append({'name':case.get('name','unnamed'), 'class':case.get('classname',''),
                          'status':'failed' if problem is not None else 'skipped' if case.find('skipped') is not None else 'passed',
                          'message':(problem.get('message','')+'\n'+(problem.text or ''))[:8000] if problem is not None else ''})
    return {'total':len(cases), **{s:sum(c['status']==s for c in cases) for s in ('passed','failed','skipped')}, 'cases':cases}
def coverage(path):
    root=ET.parse(path).getroot()
    result={'format':'jacoco' if root.tag=='report' else 'cobertura', 'files':[]}
    def ratio(covered,total): return {'covered':covered,'total':total,'percent':round(100*covered/total,1) if total else None}
    if root.tag=='report':
        for label,kind in [('line','LINE'),('branch','BRANCH')]:
            c=next((c for c in root.findall('counter') if c.get('type')==kind),None)
            if c is not None:
                hit=int(c.get('covered','0'));result[label]=ratio(hit,hit+int(c.get('missed','0')))
        for pkg in root.findall('package'):
            for f in pkg.findall('sourcefile'):
                result['files'].append(pkg.get('name','')+'/'+f.get('name',''))
    elif root.tag=='coverage':
        for label,attribute in [('line','lines'),('branch','branches')]:
            total=root.get(attribute+'-valid');hit=root.get(attribute+'-covered')
            if total is not None and hit is not None: result[label]=ratio(int(hit),int(total))
        result['files']=sorted(set(c.get('filename','') for c in root.findall('.//class')))
    else: raise ValueError('Expected JaCoCo or Cobertura coverage XML')
    result['scopeHash']=hashlib.sha256('\n'.join(sorted(result['files'])).encode()).hexdigest()
    return result

def new_run():
    cfg=config();rid=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+uuid.uuid4().hex[:6]
    services=[]
    for s in cfg['services']:
        present=any((ROOT/s['path']).glob(s['tests']))
        layers={layer:{'status':'not_configured'} for layer in LAYERS}
        layers['unit']={'status':'not_run' if present and s['runner'] else 'no_harness' if not present else 'not_configured'}
        if s['id']=='userservice':layers['integration']={'status':'not_run','description':'Existing SQLite database tests; PostgreSQL integration remains unconfigured.'}
        services.append({**s,'layers':layers})
    services.append({'id':'journeys','name':'Cross-service journeys','language':'Browser','path':'.github/workflows/ui-tests',
                     'layers':{layer:{'status':'not_run' if layer=='e2e' else 'not_applicable'} for layer in LAYERS}})
    run={'id':rid,'startedAt':now(),'finishedAt':None,'commit':git('rev-parse','HEAD'),'branch':git('branch','--show-current'),
         'dirty':bool(git('status','--porcelain')),'sourceFingerprint':fingerprint(),'services':services,
         'note':'Python database tests run separately as SQLite integration tests, not production PostgreSQL integration. Coverage is per service/layer; missing reports are not zero coverage. Existing Cypress journeys are not executed by the default collector.'}
    for suite in cfg.get('suites',[]):
        row=next(x for x in services if x['id']==suite['service']);row['layers'][suite['layer']]={'status':'not_run'}
    persist(run);save(DATA/'latest.json',{'id':rid});return run

def persist(run): save(DATA/'runs'/run['id']/'run.json',run)
def artifacts(run, folder):
    return [str(p.relative_to(DATA)).replace(os.sep,'/') for p in sorted(folder.rglob('*')) if p.is_file() and 'source' not in p.relative_to(folder).parts]

def collect(run, service, layer, command, cwd, folder, junit_patterns, coverage_pattern, timeout):
    row=next(x for x in run['services'] if x['id']==service)
    result={'status':'running','command':command,'startedAt':now(),'coverage':None,'tests':None}
    row['layers'][layer]=result;persist(run);start=time.monotonic()
    folder.mkdir(parents=True,exist_ok=True)
    try:
        with (folder/'execution.log').open('w') as log:
            process=subprocess.Popen(command,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            try: process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL);process.wait()
                if command[:2]==['docker','run'] and '--name' in command:
                    subprocess.run(['docker','rm','-f',command[command.index('--name')+1]],stdout=log,stderr=log)
                raise
        result['exitCode']=process.returncode
    except (OSError,subprocess.TimeoutExpired) as e:
        result['exitCode']=None;result['error']=str(e)
    result['durationSeconds']=round(time.monotonic()-start,2)
    paths=sorted({p for pattern in junit_patterns for p in folder.glob(pattern)})
    try:
        result['tests']=junit(paths) if paths else None
        covs=sorted(folder.glob(coverage_pattern)) if coverage_pattern else []
        if covs: result['coverage']=coverage(covs[0])
        tests=result['tests']
        result['status']='failed' if tests and tests['failed'] else 'passed' if result['exitCode']==0 and tests and tests['total']>tests['skipped'] else 'blocked'
        if result['status']=='blocked':result.setdefault('error','Execution failed, no test results were produced, or all tests were skipped. Inspect the log.')
    except Exception as e:result['status']='blocked';result['error']='Report parsing failed: '+str(e)
    result['finishedAt']=now();result['artifacts']=artifacts(run,folder);persist(run)

def execute(args):
    DATA.mkdir(parents=True,exist_ok=True)
    lock=DATA/'collector.lock'
    try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
    except FileExistsError:raise SystemExit('A collector is running (or was interrupted). Check it before removing .local/test-observability/collector.lock.')
    os.close(fd)
    try:
        run=new_run();print('Run '+run['id'],flush=True)
        for s in config()['services']:
            if args.service and s['id'] not in args.service:continue
            if any(x['service']==s['id'] and x['layer']=='unit' for x in config().get('suites',[])):continue
            if not s['runner'] or not any((ROOT/s['path']).glob(s['tests'])):continue
            folder=DATA/'runs'/run['id']/s['id']/'unit';folder.mkdir(parents=True)
            source=folder/'source';shutil.copytree(ROOT/s['path'],source,ignore=shutil.ignore_patterns('target','.venv','__pycache__','node_modules','dist','.pytest_cache'))
            if s['runner']=='java':
                cmd=['docker','run','--rm','--name','test-'+run['id']+'-'+s['id'],'-v',str(source)+':/workspace','-v','bank-demo-maven-cache:/root/.m2','-w','/workspace','maven:3.9-eclipse-temurin-17','mvn','-B','verify','-DskipITs','-Dcheckstyle.skip=true']
                junit_globs=['source/target/surefire-reports/TEST-*.xml'];cov='source/target/site/jacoco/jacoco.xml'
            else:
                (folder/'coverage.ini').write_text('[run]\nbranch = True\nsource = '+s['id']+'\nomit = */tests/*\n')
                image_result=subprocess.run(['docker','compose','images','-q',s['id']],cwd=ROOT,text=True,capture_output=True)
                image=image_result.stdout.strip() if image_result.returncode==0 else ''
                if not image:
                    row=next(x for x in run['services'] if x['id']==s['id'])
                    row['layers']['unit']={'status':'blocked','error':'Start Docker Compose services before collecting Python results'}
                    persist(run);shutil.rmtree(source);continue
                cmd=['docker','run','--rm','--name','test-'+run['id']+'-'+s['id'],'--platform','linux/amd64','-v',str(source)+':/workspace/'+s['id'],'-v',str(folder)+':/results','-w','/workspace','--entrypoint','/bin/sh',image,'-c',
                     '/bin/uv pip install --python /app/.venv/bin/python pytest==9.1.1 pytest-cov==7.1.0 && /app/.venv/bin/python -m pytest userservice/tests/test_userservice.py -o "pythonpath=/workspace /workspace/userservice" --junitxml=/results/junit.xml --cov=userservice --cov-branch --cov-config=/results/coverage.ini --cov-report=xml:/results/coverage.xml']
                junit_globs=['junit.xml'];cov='coverage.xml'
            if s['runner']=='python' and s['id']!='userservice':
                cmd[-1]=cmd[-1].replace('userservice/tests/test_userservice.py',s['id']+'/tests').replace('userservice',s['id'])
            print('Executing '+s['id']+' unit suite',flush=True)
            collect(run,s['id'],'unit',cmd,ROOT,folder,junit_globs,cov,args.timeout)
            if s['id']=='userservice' and not any(x['service']==s['id'] and x['layer']=='integration' for x in config().get('suites',[])):
                component=folder.parent/'integration';component.mkdir(parents=True)
                shutil.copy2(folder/'coverage.ini',component/'coverage.ini')
                component_cmd=[v.replace(str(folder)+':/results',str(component)+':/results').replace('test_userservice.py','test_db.py') for v in cmd]
                print('Executing userservice SQLite integration suite',flush=True)
                collect(run,s['id'],'integration',component_cmd,ROOT,component,['junit.xml'],'coverage.xml',args.timeout)
                next(x for x in run['services'] if x['id']==s['id'])['layers']['integration']['description']='Existing in-memory SQLite database tests. Does not validate PostgreSQL or cross-service integration.'
                persist(run)
            # Keep reports as downloadable evidence, discard the scratch source copy.
            if s['runner']=='java':
                for pattern in junit_globs+[cov]:
                    for p in folder.glob(pattern):shutil.copy2(p,folder/p.name)
            shutil.rmtree(source)
            result=next(x for x in run['services'] if x['id']==s['id'])['layers']['unit']
            result['artifacts']=artifacts(run,folder);persist(run)
        for suite in config().get('suites',[]):
            if args.service and suite['service'] not in args.service:continue
            folder=DATA/'runs'/run['id']/suite['service']/suite['layer']
            command=[v.replace('{output}',str(folder)).replace('{repo}',str(ROOT)) for v in suite['command']]
            collect(run,suite['service'],suite['layer'],command,ROOT/suite.get('cwd','.'),folder,suite.get('junit',['junit.xml']),suite.get('coverage'),args.timeout)
        run['finishedAt']=now();run['sourceChangedDuringRun']=fingerprint()!=run['sourceFingerprint'];persist(run)
        print('Saved '+run['id'],flush=True)
        return 1 if any(x['status'] in ('failed','blocked') for s in run['services'] for x in s['layers'].values()) else 0
    finally:lock.unlink(missing_ok=True)

def snapshot():
    runs=[]
    for p in sorted((DATA/'runs').glob('*/run.json'),reverse=True):
        try:runs.append(json.loads(p.read_text()))
        except (OSError,json.JSONDecodeError):pass
    baseline=json.loads((DATA/'baseline.json').read_text())['id'] if (DATA/'baseline.json').exists() else None
    return {'runs':runs,'baseline':baseline,'currentCommit':git('rev-parse','HEAD'),'currentFingerprint':fingerprint()}
class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.split('?')[0]=='/api/results':
            body=json.dumps(snapshot()).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
        elif self.path.startswith('/evidence/'):
            from urllib.parse import unquote
            relative=unquote(self.path[len('/evidence/'):].split('?')[0]);p=(DATA/relative).resolve()
            if not p.is_relative_to((DATA/'runs').resolve()) or not p.is_file() or p.is_symlink():self.send_error(404);return
            self.send_response(200);self.send_header('Content-Type','application/octet-stream' if p.suffix=='.zip' else 'text/plain; charset=utf-8');self.send_header('X-Content-Type-Options','nosniff');self.end_headers();self.wfile.write(p.read_bytes())
        else:super().do_GET()
    def log_message(self,*args):pass

def main():
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='action',required=True)
    r=sub.add_parser('run');r.add_argument('--service',action='append');r.add_argument('--timeout',type=int,default=600)
    sub.add_parser('inventory');b=sub.add_parser('baseline');b.add_argument('run_id')
    server=sub.add_parser('serve');server.add_argument('--port',type=int,default=8081)
    args=parser.parse_args()
    if args.action=='run':sys.exit(execute(args))
    elif args.action=='inventory':
        r=new_run();r['finishedAt']=now();persist(r);print(r['id'])
    elif args.action=='baseline':
        p=DATA/'runs'/args.run_id/'run.json'
        if not p.resolve().is_relative_to((DATA/'runs').resolve()) or not p.is_file():raise SystemExit('Unknown run')
        r=json.loads(p.read_text())
        if not r.get('finishedAt') or r.get('sourceChangedDuringRun'):raise SystemExit('Choose a completed run from an unchanged source tree')
        save(DATA/'baseline.json',{'id':args.run_id});print('Baseline frozen: '+args.run_id)
    else:
        print(f'Dashboard: http://localhost:{args.port}',flush=True)
        ThreadingHTTPServer(('127.0.0.1',args.port),partial(Handler,directory=str(HOME/'web'))).serve_forever()
if __name__=='__main__':main()
