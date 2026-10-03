#!/usr/bin/env bash
# Fresh disposable PKI, dual server/client roles, never production certificates.
set -euo pipefail
umask 077
out=$1
mkdir -p "$out"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=MTMC-peer-lab-CA -keyout "$out/ca.key" -out "$out/ca.pem" >/dev/null 2>&1
for node in a b; do
  openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$node" -keyout "$out/$node.key" -out "$out/$node.csr" >/dev/null 2>&1
  echo "subjectAltName=DNS:localhost,IP:127.0.0.1,URI:spiffe://mtmc/lab/$node" > "$out/$node.ext"
  echo 'extendedKeyUsage=serverAuth,clientAuth' >> "$out/$node.ext"
  openssl x509 -req -in "$out/$node.csr" -CA "$out/ca.pem" -CAkey "$out/ca.key" -CAcreateserial -days 2 -extfile "$out/$node.ext" -out "$out/$node.pem" >/dev/null 2>&1
done
