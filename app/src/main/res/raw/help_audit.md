The **Audit log** shows what happened to the data and when: exports, restores and security changes. It is in **Settings**, **Security**, **Audit log**.

## What it records

Each event has the time, what happened and its result, **Success** or **Failure**:

- **CSV export** and **Full backup exported**.
- **Restore** and **Restore rejected**.
- **Cloud disconnected** and **Cloud changed**.
- **App lock changed** and **Authentication failed**.
- **Backup encryption changed** and **Recovery code viewed**.
- **Currency corrected**.
- The full deletion of the data on the phone.

## What it does not record

- Amounts, names of products, customers or suppliers, or any other business content.
- Who did it: {app} is for one person and one phone.
- Sales, purchases, expenses or price changes: those stay in their own history, which is not deleted either.

## How to check it

Events are grouped by day, from the newest to the oldest. **All** shows every event, and **Failures only** shows only those that failed. If there are none, **Clear filters** goes back to the full list.

A **Failure** is not always a serious problem: a backup rejected while restoring or an identity check that did not pass are written down so it is known that they happened.

## Nothing is edited

The log is read-only: no event is edited, deleted by hand or exported. The last 5,000 events are kept: past that number, the oldest make room for the new ones.

The [full deletion of the data](help_delete_data) is the last thing written down before the phone is cleared: after that, the log goes with everything else.
