#!/usr/bin/env python3
"""Small, repeatable controls for the interview workspace."""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)

def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)

def compose(*args):
    docker = shutil.which('docker') or '/usr/local/bin/docker'
    command = [docker, '--config', str(ROOT / '.demo/docker')]
    socket = Path.home() / '.docker/run/docker.sock'
    if socket.exists():
        command += ['--host', f'unix://{socket}']
    return run(command + ['compose', '-f', 'compose.demo.json', *args])

def prepare():
    run([sys.executable, 'demo/prepare.py'])
    config = ROOT / '.demo/docker'
    config.mkdir(parents=True, exist_ok=True)
    (config / 'config.json').write_text(json.dumps({'auths':{}, 'cliPluginsExtraDirs':['/Applications/Docker.app/Contents/Resources/cli-plugins']}))

def wait(port):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            with urlopen(f'http://127.0.0.1:{port}/login', timeout=3) as response:
                if response.status == 200:
                    print(f'Bank ready: http://localhost:{port}\nLogin: testuser / bankofanthos', flush=True)
                    return
        except (URLError, TimeoutError, OSError):
            pass
        time.sleep(2)
    raise SystemExit('Bank did not become ready. Run ./demo.sh logs to inspect the services.')

def main():
    command = sys.argv[1] if len(sys.argv) > 1 else 'help'
    if command == 'start':
        prepare()
        compose('up', '-d')
        wait(8088)
    elif command == 'reference':
        prepare()
        reference = ROOT / '.demo/reference'
        if not (reference / '.git').exists():
            ref = 'refs/heads/claude-demo/reference'
            available = subprocess.run(['git', 'rev-parse', '--verify', '--quiet', ref],
                                       stdout=subprocess.DEVNULL).returncode == 0
            if not available:
                run(['git', 'fetch', 'origin',
                     'claude-demo/reference:refs/remotes/origin/claude-demo/reference'])
                ref = 'origin/claude-demo/reference'
            run(['git', 'worktree', 'add', '--detach', str(reference), ref])
        compose('--profile', 'reference', 'up', '-d', 'reference')
        wait(8089)
    elif command == 'test':
        env = os.environ.copy()
        if len(sys.argv) > 2 and sys.argv[2] == 'reference':
            env['DEMO_URL'] = 'http://127.0.0.1:8089'
        run([sys.executable, 'demo/acceptance_test.py'], env=env)
    elif command == 'check':
        sys.path.insert(0, str(ROOT / 'demo'))
        from acceptance_test import session, fetch
        client = session()
        status, _, body = fetch(client, '/home')
        assert status == 200 and 'Northstar Coffee' in body and 'Could Not Load Transactions' not in body
        assert 'Harbor Market' in body and '$999.99' not in body
        print('Login, real banking services, fixed history and account isolation: OK')
    elif command == 'status':
        compose('--profile', 'reference', 'ps')
    elif command == 'logs':
        compose('logs', '--tail', '60')
    elif command == 'stop':
        compose('--profile', 'reference', 'stop')
    elif command == 'reset':
        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
        run(['git','branch',f'rehearsal-backup-{stamp}','HEAD'])
        run(['git','stash','push','--include-untracked','-m',f'Interview rehearsal {stamp}'])
        run(['git','switch','-C','claude-demo/live','claude-demo-start'])
        print('Starting code restored. Previous work is preserved in a backup branch and git stash.')
    else:
        print('''Usage: ./demo.sh COMMAND
  start           Start the live workspace at localhost:8088
  check           Check login and the seeded account
  test            Run feature acceptance checks against the live workspace
  reference       Start the completed fallback at localhost:8089
  test reference  Run the same checks against the completed fallback
  reset           Save rehearsal work, then restore the starting code
  status          Show local service status
  logs            Show recent service logs
  stop            Stop only this demo's containers''')

if __name__ == '__main__':
    main()
