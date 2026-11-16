# Changelog

**English** · [Español](CHANGELOG.es.md)

What each version of Impulsito brought and fixed, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/). Published versions have their APK, their notes and their SHA-256 in
[Releases](https://github.com/Yesid-Robayo/Impulsito/releases).

When a new version is published, it's added at the top with the same content as its release notes.

## [1.0.0] - 2026-11-16

First public version. It's free and offered as is, **with no warranty**. It isn't accounting software and doesn't issue invoices:
its receipts are for internal use.

### Sales and customers

- **Sales in a few taps,** with several payment methods per sale, fees and net received, change, tips, discounts and held sales.
- **Voids and returns** with compensating records: nothing is deleted, and a return keeps the original cost.
- **Customers and credit:** sales on credit, payments on account, statements, credit balance and loyalty points. Only the name is
  required.
- **Cash register sessions** with expected against counted, and a count by denomination.

### Inventory and purchases

- **Products** with units and conversions, services, variants, combos and recipes, photos and your own fields.
- **Barcode scanner** with the camera or an external reader, and your own codes and labels.
- **Stock as a ledger of movements,** with batches and expiry dates, serial numbers, locations and transfers, production, suggested
  reorders and blind counts.
- **Purchases and suppliers** with average and replacement cost, purchases on credit, partial payments, due dates and returns to the
  supplier.
- **Prices:** price lists, bulk updates, margin alerts and a reference currency with a rate you type in.

### Expenses and reports

- **Expenses and recurring expenses,** and money accounts with withdrawals, contributions and transfers.
- **Profit and margin:** sales profit and final profit for the month, and taxes with the rates you type in.
- **Reports:** management summary, projected cash flow, break-even, goals, product and customer analysis, daily summary and
  comparison in real terms.
- **Import** products from CSV or a spreadsheet, and **export** everything to CSV or `.xlsx`.

### Documents

- **Sales receipts** as PDF or image, **quotes**, **orders and deliveries** and a **shareable catalog**, all internal documents.
- **Printing** on paired Bluetooth thermal printers or through the system print service.

### Backups

- **Automatic and verified backups** to a WebDAV service you choose, such as Koofr: **All saved** only appears after the backup was
  read back.
- **Backups to the phone's cloud,** today Google Drive, saved by hand with **Save to Drive** while the app is open.
- **The last good backup is never overwritten,** with a second copy on the phone and **Export a full backup** to a file.
- **Careful restore** that checks every backup before replacing anything, can go back to how things were before, and a
  **Test restore** that doesn't touch your data.
- **Optional encryption,** off by default, with a recovery code and an open format with a rescue tool in
  [`tools/backup-format`](tools/backup-format).

### Privacy and security

- **No account, no servers, no ads, no analytics and no tracking.** It works fully offline.
- **App lock** with the phone's fingerprint, face or PIN, **discreet mode** to hide amounts, an **audit log**, a screen with what data
  exists and where it goes, and **Delete my data**.
- **Check an APK** compares a file's certificate before installing it, and **Check for update** reads a signed version notice
  without sending any data. The app never installs anything by itself.

### Languages

- **Spanish, English and Portuguese,** with any currency, decimals, date and number format: nothing about your country is assumed.
- **Home screen widget, quick settings tile, shortcuts, tablet layout,** a demo with sample data, help inside the app, light and
  dark themes and nine color palettes.

[1.0.0]: https://github.com/Yesid-Robayo/Impulsito/releases/tag/v1.0.0
