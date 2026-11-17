Restocking means having what sells again before it runs out. {app} suggests how much to buy based on what is sold each day and on each product's **Minimum stock**. It is only a suggestion: it creates no purchase.

## Where to find it

- In **Products**, **Inventory**, each row has **Restock**, which opens a new purchase with that product. Above the list, **Suggested restock** opens the full list.
- In **Business**, **Suggested reorder**.
- In a product's details, **Record purchase** also opens the purchase.

The **Minimum stock** is set when creating or editing the product. With it, the product appears in **Running low** when it drops below that number. Alerts and adjustments are in [Stock, adjustments and counts](help_stock_counts).

## How it is calculated

{app} looks at what was sold in the last 30 days and works out how much goes out per day. The suggestion is what covers the days in **Cover**, plus the minimum, minus what is in stock.

For example: if 60 were sold in 30 days, 2 go out per day. With **Cover** at 30 days, 60 are needed, plus a minimum of 10, that makes 70. If 20 are left, the suggestion is to buy 50.

- If the product is bought by the box or another purchase unit, the suggestion rounds up to whole boxes.
- A product with no sales in those days says **No recent sales**. It only appears if it is below its minimum, and at 0, to fill in by hand.
- With no sales at all, the list says **Sales are needed to make a suggestion**.

## The list

Products are grouped by their usual supplier, with **No supplier** last, and in each group what is **Out of stock** comes first. Each group shows its **Estimated cost**, and each row shows what is left and how many days it lasts. The list can be filtered by **Supplier** and by **Category**.

The quantity in each row can be changed before buying. At the end of each group:

- **Share list** prepares the list to send from another app.
- **Create purchase** opens the new purchase with those quantities. Nothing is saved until you tap **Record purchase**.

## What is produced

A product the business makes with a recipe is not bought from a supplier. Its row in the inventory, in the suggested restock and in its details shows **Produce**, which opens production with that product, and it is left out of **Create purchase** and the shared list. Production is explained in [Recipes and production](help_recipes_production).
