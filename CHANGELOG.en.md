# Changelog

[Español](CHANGELOG.md) · **English**

What each version of Impulsito brought and fixed, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/). Published versions have their APK, their notes and their SHA-256 in
[Releases](https://github.com/Yesid-Robayo/Impulsito/releases).

When a new version is published, it's added at the top with the same content as its release notes.

## [1.0.7] - 2026-11-18

### Added

- **Producing by saying what it takes:** "made a cake with 200 g of flour and 3 eggs" produces with that. Without a recipe, the card
  asks what it takes and, when registered, keeps it as the recipe per unit and produces in a single transaction. With a recipe, what
  was said applies only that time; the card shows the difference and **Update the recipe** changes it too. Grams and kilos, millilitres
  and litres are converted on their own.
- **New versions are announced:** once a day, with internet and without sending any data, the app reads the version notice. If there
  is a new one, a notification arrives, once per version, and **Home** says so until it is installed. Nothing is downloaded until
  **Update** is tapped. It is turned off in **Updates and APK check**.
- **Notifications at the start:** at the end of the initial setup, a sheet explains what they are for and Android asks for the
  permission. People who already used the app are asked only once.
- **Transaction on the card:** Sale, Purchase, Count and Production at the top of the card, keeping the lines when changing it.

### Changed

- **The card adds and never clears:** with the card open, what is said adds or corrects; without a verb, it follows the card's
  operation. Another operation asks whether to start another card. The type or category of a product that already exists offers to
  open the product, without touching the card.

### Fixed

- **Counts and productions of several products:** they are registered in full, in a single transaction, instead of being rejected.

## [1.0.6] - 2026-11-18

### Added

- **Expenses in the bar:** the bottom bar is **Home**, **Sell**, **Expenses**, **Products** and **Numbers**. **Expenses** opens the month's
  expenses with **By category** on top: each category with its amount and its share of the month, and tapping it filters the list.
- **New product by sentence:** the type ("it is an ingredient", "it is for sale", "it is a service") and the category ("in the raw
  materials category") reach the card. If the category does not exist, it is created together with the product.

### Changed

- **Say, bottom right:** the microphone leaves the bar and becomes a round button on the main tabs. On tablets it stays at the top of
  the rail.
- **Understands more:** many more ways of saying each operation, verbs, amounts ("2 thousand 500", "that's 6", "50 000"), measures,
  payments ("all in cash", "she'll pay me later") and two sentences in one. Questions, plans, denials, returns and cash withdrawals
  record nothing.
- **Correcting keeps the rest:** with the card open, what is said changes only what it names: the product, the quantity, the price, the
  payment, the reason for an adjustment or the operation between sale and purchase. A sentence that corrects nothing no longer clears the
  card.

### Fixed

- **Say did not listen on some phones:** the voice service got the language without a country and closed at once. Now it is asked with
  a country, the language the phone has downloaded is used, and each installed voice service is tried.
- **A describing sentence no longer invents a product:** "this is an ingredient" does not become another line.

## [1.0.5] - 2026-11-17

### Changed

- **Say, in the center:** the button at the bottom center is **Say**. It listens right away with its own screen, which shows the
  sentence as you say it, and opens the card. It uses the phone's voice service without its window, and the first time it asks for the
  microphone after explaining why. It can be turned off in **Settings**; off, or without the permission, the keyboard remains.
- **The bottom bar:** **Home**, **Sell**, **Say**, **Products** and **Numbers**. **Records** opens from **Home**, and selling by hand is
  also in **Sell by hand** inside **Say**, on the widget and on the icon shortcut.
- **Quantities:** a whole quantity shows without decimals, **10 kg** and not 10,000 kg; one that is not whole keeps its three
  decimals.

### Fixed

- **Dictated amounts:** "50,000" with the dictation's thousands comma is fifty thousand, not fifty.
- **Microphone not working?**, in **Say**, opens the steps to make it work.

## [1.0.4] - 2026-11-17

### Added

- **Say or type:** a spoken or typed sentence, such as "sold 3 bread rolls and 2 juices for cash", shows on a card with each product,
  its amount and the total, and is recorded only when you tap **Register**. It understands sales, purchases, expenses, production,
  stock adjustments, customer and supplier payments, recipes, new products, and cost and price changes, in Spanish, English and
  Portuguese. Anything doubtful is asked, and you correct the card by tapping a line or with a short sentence such as "no, it was 3".
  It opens from **Home**, the widget and the icon shortcut. It works offline: dictate with the keyboard microphone.
- **Direct voice, optional:** in **Settings**, off at first, **Say** can open the phone's voice service. The app never asks for the
  microphone.

### Fixed

- **Edit product:** with movements, the cost says it updates with each purchase and offers **Correct cost**. Before, the field was
  locked without saying where to change it.

## [1.0.3] - 2026-11-17

### Added

- **Complete help:** 25 new articles, among them recipes and production, purchases and suppliers, accounts payable, expenses,
  restocking, categories, variants, locations, scanner and labels, price lists, Numbers, goals, records, preferences, widget and
  the **Business** hub. Help is grouped by what you want to do, and the help button of each screen opens its article.

### Fixed

- **Production** without recipes: **How to create a recipe** explains the steps. Before, it opened the combos, which are not
  produced.
- **Products:** editing a product no longer removes the usual supplier that an import gave it.
- **Help:** two labels that no longer matched the app.

## [1.0.2] - 2026-11-17

### Added

- **One-tap update:** when **Check for update** finds a new version, **Update** downloads it, checks it's the one announced in the
  signed notice and carries the app's signature, and Android asks to confirm. The first time, Android asks to allow Impulsito to
  install its updates.
- **What's new,** in **Updates and APK check**: what each version brought, offline.
- **Produce to restock:** a product with a recipe offers **Produce** in the inventory, the suggested restock and its detail, and opens
  production with that product chosen.
- **Total cost of the opening stock:** when creating a product with opening stock, you can type the total paid instead of the unit
  cost. The stock value stays exact, also for cheap products sold by the gram.

### Fixed

- **Suppliers,** in the detail of a product without purchases: the notice no longer sits against the edge.

## [1.0.1] - 2026-11-17

### Fixed

- **Backups:** if the system stopped a backup halfway or the network made it wait, the backup could stay in the cloud without its
  metadata. Now it is kept until the backup finishes.
- **WebDAV backups:** the connection test says when a step is slow, stops after 5 minutes without saving anything and offers to
  retry.
- **Numbers:** the chart no longer picks a day that hasn't come yet as the lowest of the period.
- **Texts:** with the phone's cloud, help and steps name the **Save to Drive** button. The time of the last backup no longer ends
  with a double period.

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
