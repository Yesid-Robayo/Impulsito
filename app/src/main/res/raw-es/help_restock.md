Reponer es volver a tener lo que se vende antes de que se acabe. {app} sugiere cuánto comprar a partir de lo que se vende cada día y del **Stock mínimo** de cada producto. Es solo una sugerencia: no crea ninguna compra.

## Dónde está

- En **Productos**, **Inventario**, cada fila trae **Reponer**, que abre una compra nueva con ese producto. Arriba de la lista, **Reposición sugerida** abre la lista completa.
- En **Negocio**, **Reposición sugerida**.
- En el detalle de un producto, **Registrar compra** también abre la compra.

El **Stock mínimo** se pone al crear o editar el producto. Con él, el producto aparece en **Por agotarse** cuando baja de ese número. Los avisos y los ajustes están en [Stock, ajustes y conteos](help_stock_counts).

## Cómo se calcula

{app} mira lo vendido en los últimos 30 días y saca cuánto sale por día. Lo sugerido es lo que alcanza para los días de **Cubrir**, más el mínimo, menos lo que hay.

Por ejemplo: si en 30 días se vendieron 60, salen 2 por día. Con **Cubrir** en 30 días hacen falta 60, más un mínimo de 10, son 70. Si quedan 20, se sugiere comprar 50.

- Si el producto se compra por caja u otra unidad de compra, lo sugerido sube a cajas enteras.
- Un producto sin ventas en esos días dice **Sin ventas recientes**. Solo aparece si está bajo su mínimo, y con 0, para llenarlo a mano.
- Sin ninguna venta, la lista dice **Hacen falta ventas para sugerir**.

## La lista

Los productos van agrupados por su proveedor habitual, con **Sin proveedor** al final, y en cada grupo primero lo **Agotado**. Cada grupo muestra su **Costo estimado**, y cada fila, lo que queda y para cuántos días alcanza. Se puede filtrar por **Proveedor** y por **Categoría**.

La cantidad de cada fila se cambia antes de comprar. Al final de cada grupo:

- **Compartir lista** prepara la lista para enviarla desde otra app.
- **Crear compra** abre la compra nueva con esas cantidades. Nada se guarda hasta tocar **Registrar compra**.

## Lo que se produce

Un producto que el negocio hace con una receta no se le compra a un proveedor. En su fila del inventario, en la reposición sugerida y en su detalle aparece **Producir**, que abre la producción con ese producto, y queda fuera de **Crear compra** y de la lista compartida. La producción se explica en [Recetas y producción](help_recipes_production).
