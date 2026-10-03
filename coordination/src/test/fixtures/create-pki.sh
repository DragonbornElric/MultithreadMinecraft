#!/usr/bin/env bash
# Disposable lab CA. Never use these generated certificates outside this run.
set -euo pipefail
umask 077
out=$1
mkdir -p "$out"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=MTMC-test-CA -keyout "$out/ca.key" -out "$out/ca.pem" >/dev/null 2>&1
for node in server lobby a b outsider wrongcluster; do
  openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$node" -keyout "$out/$node.key" -out "$out/$node.csr" >/dev/null 2>&1
  if [[ $node == server ]]; then
    echo 'subjectAltName=DNS:localhost,IP:127.0.0.1' > "$out/$node.ext"
    echo 'extendedKeyUsage=serverAuth' >> "$out/$node.ext"
  else
    cluster=lab
    [[ $node != wrongcluster ]] || cluster=elsewhere
    echo "subjectAltName=URI:spiffe://mtmc/$cluster/$node" > "$out/$node.ext"
    echo 'extendedKeyUsage=clientAuth' >> "$out/$node.ext"
  fi
  openssl x509 -req -in "$out/$node.csr" -CA "$out/ca.pem" -CAkey "$out/ca.key" -CAcreateserial -days 2 -extfile "$out/$node.ext" -out "$out/$node.pem" >/dev/null 2>&1
done
