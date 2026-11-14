# Glossary

The words used in the code, the API and the docs. One meaning per word.

## Business

| Term | Arabic | Meaning |
|---|---|---|
| Payment | عملية دفع | One attempt to take one amount for one order. |
| Gateway | بوابة الدفع | The company that moves the money between the customer's bank and the store. |
| Card scheme | شبكة البطاقة | The network of a card: mada, Visa, Mastercard. |
| Token | رمز البطاقة | A one-time stand-in for a card, made by the gateway's page. Mizan never sees the card number. |
| 3-D Secure | التحقق برمز البنك | The extra step where the customer's bank asks for a one-time code. |
| Pay later | ادفع لاحقًا | The customer pays a provider in parts; the provider pays the store in full. |
| Authorization (hold) | حجز المبلغ | The bank sets the amount aside for the store. No money has moved yet. |
| Capture | تحصيل المبلغ | Taking the held money. |
| Void | إلغاء الحجز | Releasing a hold before capture. |
| Refund | استرداد | Giving captured money back, in full or in part. |
| Late capture | تحصيل متأخر | Money that was taken after Mizan told the store the payment was dead. |
| Fee | رسوم البوابة | What the gateway keeps from each payment. |
| VAT | ضريبة القيمة المضافة | 15% tax, charged on the fee. |
| Settlement | تسوية | The gateway paying the store for many payments at once, minus fees. |
| Payout | تحويل التسوية | The one bank transfer of a settlement. |
| Settlement file | ملف التسوية | The gateway's list of what one payout contains. |
| Reconciliation | مطابقة | Checking that what was paid out matches what was sold. |
| Discrepancy | فرق | Something that does not match. |

## Books

| Term | Arabic | Meaning |
|---|---|---|
| Ledger | دفتر الأستاذ | The book of all money facts. |
| Entry | قيد | One money fact, with equal debits and credits. |
| Posting | طرف القيد | One side of an entry: an account, debit or credit, an amount. |
| Debit, credit | مدين، دائن | The two sides of an entry. |
| Account | حساب | A named bucket of money in the ledger. |
| Receivable | ذمم مدينة | Money someone owes the store. Here: the gateway, for captured payments not yet paid out. |
| Suspense | حساب معلّق | Money that arrived and is not explained yet. |
| Trial balance | ميزان المراجعة | The check that all debits equal all credits. |

## Technical

| Term | Meaning |
|---|---|
| Sync | Copying the gateway's record of a payment into Mizan. The only code that changes a payment's money. |
| Snapshot | The gateway's record of one payment at one moment, in Mizan's own shape. |
| Operation | One thing Mizan asks the gateway to do: create, capture, cancel, refund. |
| In flight | An operation that was sent and has no known result yet. |
| Follow-up | An operation Mizan starts by itself: automatic capture, void or refund. |
| Webhook | A call from the gateway to Mizan that says something changed. A hint, not a fact. |
| Idempotency key | A value the caller sends so that the same request, sent twice, has the effect of one. |
| Outbox | A table of events written in the same transaction as the change, and sent later. |
| Lease | A short claim on a row, so that two workers do not work on it together. |
| Halala | 1/100 of a riyal. All amounts are whole halalas. |
| Simulator | A fake gateway that can fail on purpose and keeps its own book of what really happened. |
| Proof run | Many payments with planned faults, then a comparison of Mizan with the simulator's book. |
| Anomaly | A gateway record that cannot be true: it moved backwards, or its amount changed. |
