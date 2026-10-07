#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker info >/dev/null
mkdir -p .local/keys
if [[ ! -f .local/keys/privatekey ]]; then
  openssl genrsa -out .local/keys/privatekey 2048
fi
openssl rsa -in .local/keys/privatekey -pubout -out .local/keys/publickey 2>/dev/null
docker compose up -d
printf '\nDemo Bank: http://localhost:8080\nDemo login: testuser / bankofanthos\nFirst startup can take several minutes. Check: docker compose logs --tail 30\n'
