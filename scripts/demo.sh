#!/usr/bin/env bash
# A short tour of Mizan, against the containers of docker-compose.yml. It needs Docker, curl and python3.
#
#   ./mvnw package -DskipTests && scripts/demo.sh
#
# Every step prints what it did and what Mizan answered. The simulator plays both gateways.
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080
SIM=http://localhost:8090
CARD_AUTH="sk_test_simulator:"      # the simulator's own test key, not a secret
RUN=$(date +%s)

field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval('d' + ''.join('[%r]' % (int(k) if k.isdigit() else k) for k in sys.argv[1].split('.'))))" "$1"; }
step() { printf '\n== %s\n' "$*"; }
token() { curl -s -u "$CARD_AUTH" -H 'Content-Type: application/json' -d "{\"number\":\"58884500000$1\",\"scheme\":\"mada\"}" "$SIM/card/v1/tokens" | field id; }
pay() { # order, amount, card's last 4, capture
  curl -s -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $1-$RANDOM" -H 'Content-Type: application/json' \
    -d "{\"amount\":$2,\"currency\":\"SAR\",\"reference\":\"$1\",\"method\":\"CARD\",\"source\":{\"token\":\"$(token "0$3")\"},\"capture\":\"$4\",\"return_url\":\"http://localhost:3000/orders/$1\"}" \
    "$API/v1/payments"
}
get() { curl -s -H "Authorization: Bearer $KEY" "$API$1"; }
post() { curl -s -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $RANDOM-$RANDOM" -H 'Content-Type: application/json' -d "${2:-{\}}" "$API$1"; }

step "Start Mizan, PostgreSQL and the simulator"
docker compose up --build -d --wait >/dev/null 2>&1
KEY=$(docker compose run --rm -T mizan keys create --name="demo-$RUN" --scopes=payments,backoffice 2>/dev/null | grep '^mzn_')
echo "An API key was made with the command line. It is shown once."

step "1. A card that pays at once"
P1=$(pay "demo-$RUN-1" 15000 1111 AUTO)
echo "status=$(echo "$P1" | field status) captured=$(echo "$P1" | field captured_amount) card=$(echo "$P1" | field card.last4)"

step "2. A card that needs the bank's code: the customer goes to the bank's page"
P2=$(pay "demo-$RUN-2" 8000 3333 AUTO)
ID2=$(echo "$P2" | field id)
echo "status=$(echo "$P2" | field status) pending_reason=$(echo "$P2" | field pending_reason)"
sleep 3   # the customer types the bank's code
curl -s -o /dev/null -X POST "$SIM/card/3ds/$ID2?result=approve"
echo "The customer approved. The gateway sends them back to Mizan's return page:"
echo "  $(curl -s -o /dev/null -w '%{http_code} -> %{redirect_url}' "$API/v1/return/$ID2")"
echo "status=$(get "/v1/payments/$ID2" | field status)"

step "3. A declined card is a payment that failed, not an error"
P3=$(pay "demo-$RUN-3" 5000 0002 AUTO)
echo "status=$(echo "$P3" | field status) failure=$(echo "$P3" | field failure.message)"

step "4. The gateway charges the card and the answer is lost on the way"
curl -s -o /dev/null -H 'Content-Type: application/json' -d "{\"reference\":\"demo-$RUN-4\",\"call\":\"CREATE\",\"fault\":\"LOST_ANSWER\"}" "$SIM/_sim/fault"
P4=$(pay "demo-$RUN-4" 12000 1111 AUTO)
echo "status=$(echo "$P4" | field status) captured=$(echo "$P4" | field captured_amount)  (Mizan asked the gateway by its own id)"

step "5. A hold, captured in part, then a part refund"
P5=$(pay "demo-$RUN-5" 20000 1111 MANUAL)
ID5=$(echo "$P5" | field id)
echo "status=$(echo "$P5" | field status)"
echo "capture 15000: $(post "/v1/payments/$ID5/capture" '{"amount":15000}' | field status)"
echo "refund 4000:   $(post "/v1/payments/$ID5/refunds" '{"amount":4000,"reason":"One item returned"}' | field status)"
echo "refund 20000:  $(post "/v1/payments/$ID5/refunds" '{"amount":20000}' | field code)"

step "6. The same request twice makes one payment"
BODY="{\"amount\":3000,\"currency\":\"SAR\",\"reference\":\"demo-$RUN-6\",\"method\":\"CARD\",\"source\":{\"token\":\"$(token 01111)\"},\"return_url\":\"http://localhost:3000/orders/6\"}"
A=$(curl -s -H "Authorization: Bearer $KEY" -H "Idempotency-Key: demo-$RUN-6" -H 'Content-Type: application/json' -d "$BODY" "$API/v1/payments" | field id)
B=$(curl -s -H "Authorization: Bearer $KEY" -H "Idempotency-Key: demo-$RUN-6" -H 'Content-Type: application/json' -d "$BODY" "$API/v1/payments" | field id)
[ "$A" = "$B" ] && echo "same payment both times: $A"

step "7. The gateway pays out. Its settlement file has a wrong fee on one line"
curl -s -o /dev/null -H 'Content-Type: application/json' -d "{\"fault\":\"WRONG_FEE\",\"reference\":\"demo-$RUN-1\"}" "$SIM/_sim/file-fault"
DAY=$(date -u -d "+$((RUN % 1000)) days" +%F 2>/dev/null || date -u -v+"$((RUN % 1000))"d +%F)
FILE=$(curl -s -u "$CARD_AUTH" "$SIM/card/v1/settlements/$DAY")
UPLOADED=$(curl -s -H "Authorization: Bearer $KEY" -H 'Content-Type: text/csv' --data-binary "$FILE" "$API/v1/settlements")
FILE_ID=$(echo "$UPLOADED" | field id)
sleep 3   # the matching worker runs every second
echo "lines: $(get "/v1/settlements/$FILE_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["lines"])')"
get "/v1/discrepancies?status=OPEN&type=FEE_MISMATCH" | python3 -c '
import json,sys
for d in json.load(sys.stdin)["data"][:1]:
    print("discrepancy %s: expected fee %s, the file says %s" % (d["type"], d["expected"], d["actual"]))'

step "8. The books balance"
get /v1/ledger/balances | python3 -c '
import json,sys
b=json.load(sys.stdin)
for a in b["accounts"]: print("  %-20s %10d" % (a["account"], a["balance"]))
print("  balanced: %s" % b["balanced"])'

step "Done. Stop it with: docker compose down -v"
