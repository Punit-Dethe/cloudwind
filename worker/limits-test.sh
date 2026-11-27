#!/usr/bin/env bash
# Guard tests. Needs a Worker started with tiny limits, e.g. via `wrangler dev` with .dev.vars:
#   INBOX_CAP_BYTES=3000000 SOFT_FRACTION=0.6 MAX_OBJECT_BYTES=2000000 MAX_DOWNLOADS=2
# Usage: BASE=http://127.0.0.1:8787 PHONE=... AGENT=... ./limits-test.sh
set -uo pipefail
: "${BASE:?}" "${PHONE:?}" "${AGENT:?}"
T=$(mktemp -d); trap 'rm -rf $T' EXIT
fail=0
ok() { if [ "$1" = "$2" ]; then echo "ok   $3"; else echo "FAIL $3 (got $1, want $2)"; fail=1; fi; }
has() { if grep -q "$2" "$1"; then echo "ok   $3"; else echo "FAIL $3 (missing $2 in: $(head -c 200 $1))"; fail=1; fi; }
P=(-H "authorization: Bearer $PHONE"); A=(-H "authorization: Bearer $AGENT")
rid() { head -c16 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
put() { curl -s -o ${3:-$T/out} -w '%{http_code}' -X PUT "${P[@]}" --data-binary @$2 $BASE/v1/objects/$1; }

head -c 1000000 /dev/urandom > $T/m1; head -c 1000000 /dev/urandom > $T/m1b; head -c 1500000 /dev/urandom > $T/m15; head -c 2500000 /dev/urandom > $T/m25
IA=$(rid); IB=$(rid); IC=$(rid)

ok "$(put $IA $T/m1)" 201 "A (1.0 MB) fits"
ok "$(put $IB $T/m1b)" 201 "B (1.0 MB) fits, inbox now 2.0 of 3.0 MB"
ok "$(put $IC $T/m15)" 507 "C (1.5 MB) refused: inbox full (nothing synced to evict)"
has $T/out '"free_bytes":1000000' "507 tells the phone how much room is left"
ok "$(put $(rid) $T/m25)" 413 "2.5 MB file refused: over per-object max"
has $T/out 'object_too_large' "413 names the reason"

ok "$(curl -s -o /dev/null -w '%{http_code}' -X POST "${A[@]}" $BASE/v1/objects/$IA/synced)" 200 "PC confirms A"
ok "$(put $IC $T/m15)" 201 "C now fits: synced A was evicted to make room"
ok "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" $BASE/v1/objects/$IA)" 404 "evicted A is gone from the cloud"
curl -s "${P[@]}" "$BASE/v1/status" > $T/st
has $T/st "\"id\":\"$IA\",\"state\":\"synced\"" "phone still sees A as synced (safe to delete)"
has $T/st '"max_object_bytes":2000000' "status exposes limits"

# multipart reservations
ID=$(rid)
ok "$(curl -s -o $T/out -w '%{http_code}' -X POST "${P[@]}" $BASE/v1/objects/$ID/multipart)" 400 "multipart start without size refused"
ok "$(curl -s -o $T/out -w '%{http_code}' -X POST "${P[@]}" -H 'content-type: application/json' -d '{"size":2500000}' $BASE/v1/objects/$ID/multipart)" 413 "multipart start over max refused"
ok "$(curl -s -o $T/out -w '%{http_code}' -X POST "${P[@]}" -H 'content-type: application/json' -d '{"size":900000}' $BASE/v1/objects/$ID/multipart)" 507 "multipart start refused when reservation wouldn't fit"
has $T/out 'inbox_full' "reason is inbox_full"
ok "$(curl -s -o /dev/null -w '%{http_code}' -X POST "${A[@]}" $BASE/v1/objects/$IB/synced)" 200 "PC confirms B"
ok "$(curl -s -o $T/out -w '%{http_code}' -X POST "${P[@]}" -H 'content-type: application/json' -d '{"size":400000}' $BASE/v1/objects/$ID/multipart)" 201 "multipart start fits after B evicted"
UP=$(sed -E 's/.*"uploadId":"([^"]+)".*/\1/' $T/out)
curl -s "${P[@]}" $BASE/v1/status | grep -q '"inbox_bytes":1900000' && echo "ok   reservation counted (1.5 MB + 0.4 MB reserved)" || { echo "FAIL reservation not counted: $(curl -s "${P[@]}" $BASE/v1/status | grep -o '"inbox_bytes":[0-9]*')"; fail=1; }
head -c 100 /dev/urandom > $T/part
PART=$(curl -s -X PUT "${P[@]}" --data-binary @$T/part $BASE/v1/objects/$ID/multipart/$UP/1)
ok "$(curl -s -o $T/out -w '%{http_code}' -X POST "${P[@]}" -H 'content-type: application/json' -d "{\"parts\":[$PART]}" $BASE/v1/objects/$ID/multipart/$UP/complete)" 201 "multipart completes when actual <= declared"
ID2=$(rid)
curl -s -o $T/out -X POST "${P[@]}" -H 'content-type: application/json' -d '{"size":50}' $BASE/v1/objects/$ID2/multipart
UP2=$(sed -E 's/.*"uploadId":"([^"]+)".*/\1/' $T/out)
PART2=$(curl -s -X PUT "${P[@]}" --data-binary @$T/part $BASE/v1/objects/$ID2/multipart/$UP2/1)
ok "$(curl -s -o $T/out -w '%{http_code}' -X POST "${P[@]}" -H 'content-type: application/json' -d "{\"parts\":[$PART2]}" $BASE/v1/objects/$ID2/multipart/$UP2/complete)" 400 "under-declared size rejected"
ok "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" $BASE/v1/objects/$ID2)" 404 "and its data is dropped"

# download cap and ranged resume
ok "$(curl -s -o $T/d1 -w '%{http_code}' "${A[@]}" $BASE/v1/objects/$IC)" 200 "download 1"
ok "$(curl -s -o $T/d2 -w '%{http_code}' "${A[@]}" $BASE/v1/objects/$IC)" 200 "download 2"
ok "$(curl -s -o $T/out -w '%{http_code}' "${A[@]}" $BASE/v1/objects/$IC)" 429 "download 3 refused (cap = 2)"
has $T/out download_limit "429 names download_limit"
ok "$(curl -s -o $T/tail -w '%{http_code}' "${A[@]}" -H 'Range: bytes=1000-' $BASE/v1/objects/$IC)" 206 "ranged resume still allowed past the cap"
tail -c +1001 $T/m15 | cmp - $T/tail && echo "ok   resumed bytes match" || { echo "FAIL resumed bytes differ"; fail=1; }
ok "$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" -H 'Range: bytes=9999999-' $BASE/v1/objects/$IC)" 416 "range past end -> 416"

# resume support: an upload the server no longer knows must yield a recoverable 404
ok "$(curl -s -o $T/out -w '%{http_code}' -X PUT "${P[@]}" --data-binary @$T/part $BASE/v1/objects/$(rid)/multipart/not-a-real-upload/1)" 404 "part for a vanished upload -> 404"
has $T/out unknown_upload "reason is unknown_upload (phone restarts the upload)"

# watchdog: the PC can see whether the phone is alive and what's waiting
ok "$(curl -s -o $T/h -w '%{http_code}' "${A[@]}" $BASE/v1/health)" 200 "agent can read health"
has $T/h '"phone_last_seen":[0-9]' "phone_last_seen recorded from phone requests"
has $T/h '"pending_count"' "health reports pending count"
ok "$(curl -s -o /dev/null -w '%{http_code}' "${P[@]}" $BASE/v1/health)" 403 "phone cannot read health"

# rate limit (keep last: it throttles this role for a minute)
n429=0; for i in $(seq 1 700); do c=$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" $BASE/v1/pending); [ "$c" = 429 ] && n429=$((n429+1)); done
[ $n429 -gt 0 ] && echo "ok   rate limit kicked in ($n429 of 700 refused)" || { echo "FAIL no 429 after 700 requests"; fail=1; }
[ $fail = 0 ] && echo "ALL PASSED" || { echo "SOME FAILED"; exit 1; }
