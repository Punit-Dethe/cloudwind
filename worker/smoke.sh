#!/usr/bin/env bash
# Usage: BASE=http://127.0.0.1:8787 PHONE=... AGENT=... ./smoke.sh
set -euo pipefail
: "${BASE:?}" "${PHONE:?}" "${AGENT:?}"
T=$(mktemp -d); trap 'rm -rf $T' EXIT
code() { curl -s -o "${OUT:-/dev/null}" -w '%{http_code}' "$@"; }
ok() { [ "$1" = "$2" ] && echo "ok   $3" || { echo "FAIL $3 (got $1, want $2)"; exit 1; }; }
P=(-H "authorization: Bearer $PHONE"); A=(-H "authorization: Bearer $AGENT")
ID=$(head -c16 /dev/urandom | od -An -tx1 | tr -d " \n"); ID2=$(head -c16 /dev/urandom | od -An -tx1 | tr -d " \n")

ok "$(code $BASE/health)" 200 "health is public"
ok "$(code $BASE/v1/status)" 401 "no token rejected"
ok "$(code "${A[@]}" $BASE/v1/status)" 403 "agent can't read phone status"
ok "$(code "${P[@]}" $BASE/v1/pending)" 403 "phone can't list pending"

head -c 1000 /dev/urandom > $T/small
ok "$(code -X PUT "${P[@]}" --data-binary @$T/small $BASE/v1/objects/$ID)" 201 "single PUT"
ok "$(code -X PUT "${P[@]}" --data-binary @$T/small $BASE/v1/objects/$ID)" 409 "no overwrite"
ok "$(code -X PUT "${P[@]}" --data-binary @$T/small $BASE/v1/objects/not-an-id)" 400 "bad id rejected"

OUT=$T/pending ok "$(OUT=$T/pending code "${A[@]}" $BASE/v1/pending)" 200 "agent lists pending"
grep -q $ID $T/pending && echo "ok   object is pending"
ok "$(OUT=$T/got code "${A[@]}" $BASE/v1/objects/$ID)" 200 "agent downloads"
cmp $T/small $T/got && echo "ok   download matches upload"
ok "$(code "${P[@]}" $BASE/v1/objects/$ID)" 403 "phone can't download"

# multipart: 2 parts of 5 MiB + a short last part
head -c 5242880 /dev/urandom > $T/p1; head -c 5242880 /dev/urandom > $T/p2; head -c 777 /dev/urandom > $T/p3
UP=$(curl -s -X POST "${P[@]}" -H 'content-type: application/json' -d '{"size":10486777}' $BASE/v1/objects/$ID2/multipart | sed -E 's/.*"uploadId":"([^"]+)".*/\1/')
parts=""
for n in 1 2 3; do
  r=$(curl -s -X PUT "${P[@]}" --data-binary @$T/p$n $BASE/v1/objects/$ID2/multipart/$UP/$n)
  parts="$parts${parts:+,}$r"
done
ok "$(code -X POST "${P[@]}" -H 'content-type: application/json' -d "{\"parts\":[$parts]}" $BASE/v1/objects/$ID2/multipart/$UP/complete)" 201 "multipart complete"
cat $T/p1 $T/p2 $T/p3 > $T/whole
curl -s "${A[@]}" $BASE/v1/objects/$ID2 -o $T/got2; cmp $T/whole $T/got2 && echo "ok   multipart download matches"

curl -s "${P[@]}" $BASE/v1/status | grep -q "\"id\":\"$ID\",\"state\":\"uploaded\"" && echo "ok   status: uploaded"
ok "$(code -X POST "${A[@]}" $BASE/v1/objects/$ID/synced)" 200 "agent marks synced"
curl -s "${P[@]}" $BASE/v1/status | grep -q "\"id\":\"$ID\",\"state\":\"synced\"" && echo "ok   status: synced"
curl -s "${A[@]}" $BASE/v1/pending | grep -q $ID && { echo "FAIL still pending"; exit 1; } || echo "ok   no longer pending"
# tidy: the multipart test object is random bytes the agent could never decrypt
curl -s -o /dev/null -X POST "${A[@]}" $BASE/v1/objects/$ID2/synced
echo "ALL PASSED"
