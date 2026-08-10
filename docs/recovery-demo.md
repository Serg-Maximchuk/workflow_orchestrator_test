# Recovery demo: killing the application mid-order

The point of an embedded engine, demonstrated by hand in about three minutes. An order is put in
flight, the application is killed while the order is waiting on the supplier, and a new instance
picks the order up and finishes it - without repeating a single supplier call.

Nothing in this repository implements recovery. That is the claim being demonstrated: the engine
keeps its state in the same database as the order, so a restart is not an event the application has
to handle.

The same thing is asserted automatically in
[`MidProcessRestartIT`](../engine-flowable/src/test/java/com/example/sil/recovery/MidProcessRestartIT.java);
this document is the version you can watch.

## 1. Start the stack

```bash
docker compose down -v && docker compose up -d
```

Postgres, WireMock standing in for the supplier, RabbitMQ for the outbox. Start from an empty
volume - the database is durable by design, so orders from an earlier run are still in it and the
counts below would include them.

## 2. Start the application

```bash
SIL_SHIPMENT_POLL_DELAY=PT10S ./gradlew :engine-flowable:bootRun
```

The shorter poll interval is passed in rather than hard-coded in the model, so a demo does not have
to wait six hours for the shipment check.

## 3. Submit an order

```bash
curl -s -X POST localhost:8080/tmf-api/serviceOrdering/v4/serviceOrder \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: recovery-demo' \
  -d '{"externalId":"OMS-DEMO","customer":{"name":"Acme Ltd","email":"ops@acme.example"},
       "place":{"postcode":"SW1A 1AA"},"serviceSpecId":"VOIP_BUSINESS","speedMbps":100}'
```

Keep the returned `id`:

```bash
ORDER=so-...
```

## 4. Watch it park

```bash
curl -s localhost:8080/tmf-api/serviceOrdering/v4/serviceOrder/$ORDER | python3 -m json.tool
```

Within a second the four provisioning calls are done and the order is waiting for the supplier's
activation callback:

```json
"state": "inProgress",
"supplierRefs": {"customerId": "...", "subscriptionId": "...", "userId": "...", "phoneNumber": "+4420..."}
```

What that looks like in the database - the state that is about to survive the kill:

```bash
docker exec sil-postgres psql -U sil -d sil -c \
  "select count(*) as waiting_messages from act_ru_event_subscr"
docker exec sil-postgres psql -U sil -d sil -c \
  "select count(*) as armed_timers from act_ru_timer_job"
```

On a clean database: one message subscription - the callback the process is parked on - and two
timers, the activation SLA and the customer reminder. This is the state that is about to outlive
the application.

## 5. Kill it

Stop `bootRun` with `Ctrl+C`, or less politely:

```bash
pkill -9 -f com.example.sil.ServiceIntegrationLayerApplication
```

There is now no process anywhere that knows about this order. The supplier, meanwhile, has four
resources belonging to it.

## 6. Start it again

```bash
SIL_SHIPMENT_POLL_DELAY=PT10S ./gradlew :engine-flowable:bootRun
```

## 7. Deliver the callback the supplier would have sent anyway

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/callbacks/voip/number-activation \
  -H 'Content-Type: application/json' \
  -d "{\"orderId\":\"$ORDER\",\"activated\":true,\"reason\":\"\"}"
```

`202`. The callback correlated to a process this instance never started.

## 8. Watch it finish

```bash
curl -s localhost:8080/tmf-api/serviceOrdering/v4/serviceOrder/$ORDER | python3 -m json.tool
```

After the poll timer comes round: `"state": "completed"`.

## 9. The assertion that matters

Nothing was redone. Count what the supplier actually received:

```bash
curl -s localhost:8081/__admin/requests | python3 -c "
import json,sys,collections
counts = collections.Counter(
    r['request']['url'] for r in json.load(sys.stdin)['requests']
    if r['request']['method'] == 'POST' and '/supplier/' in r['request']['url'])
for url, n in sorted(counts.items()): print(f'{n}  {url}')"
```

Each provisioning operation appears exactly **once**, across two lifetimes of the application. Had
this journey been a service method with a retry loop, the kill would have lost not the order row -
that is committed - but the knowledge of where it had got to and what still had to happen. That is
the reconciliation job nobody wants to write.

And the timeline reads as one continuous journey, because it is one process instance:

```bash
curl -s localhost:8080/tmf-api/serviceOrdering/v4/serviceOrder/$ORDER/timeline | python3 -m json.tool
```

## Variant: kill it while a timer is pending

Between steps 7 and 8, kill the application again while the order is asleep on the shipment poll
timer, wait past the interval, and start it back up. The timer is a row with a due date in the past,
so the new instance's job executor finds it immediately and the poll happens late rather than never.

## What the listener saw

The completion was announced through the transactional outbox, which drained on the new instance:

```bash
docker exec sil-postgres psql -U sil -d sil -c \
  "select count(*) filter (where published_at is null) as pending, count(*) as total from outbox_event"

curl -s 'localhost:8081/__admin/requests?limit=200' | python3 -c "
import json,sys
hits=[r for r in json.load(sys.stdin)['requests'] if '/listener/order-events' in r['request']['url']]
print(len(hits), 'deliveries; last:', hits[0]['request']['body'] if hits else '-')"
```

The event was written in the same transaction as the order's completion, so "the order completed"
and "somebody was told" cannot get out of step - not even across the restart.

## Tear down

```bash
docker compose down -v
```
