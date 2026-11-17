Una lista de precios guarda precios distintos para un tipo de cliente, como los mayoristas, sin duplicar productos. Siempre existe **Lista general**: sus precios son los precios de venta de los productos.

## Crear una lista

En **Negocio**, **Listas de precios**, tocar **Nueva lista**:

- **Nombre**: por ejemplo, mayoristas.
- **Tipo**: **Fija**, con un precio escrito para cada producto, o **Relativa**, que calcula sus precios desde una **Lista base** con un **Ajuste** en porcentaje. Con un ajuste de -10, un producto que en la base vale 100 queda en 90.
- **Guardar lista**.

En una lista fija, un producto sin precio propio usa el precio general y lo indica con **Precio general**. En una relativa, cada precio aparece como **Calculado**.

## Cambiar un precio

En el detalle de una lista fija, tocar un producto, escribir el **Precio en esta lista** y tocar **Guardar**. Mientras se escribe se ve el **Margen resultante**. **Quitar precio propio** devuelve el producto al precio general. En **Lista general**, cambiar un precio cambia el precio de venta del producto.

**Actualizar precios de esta lista** cambia muchos precios a la vez: por **Porcentaje**, **Monto**, **Margen objetivo** o **Por cotización**.

Cada cambio queda en el historial del producto, en la pestaña **Precios** de su detalle. Las ventas pasadas no cambian: cada una guarda el precio con que se vendió.

## Usar la lista al vender

- En el detalle del cliente, **Editar** → **Lista de precios** le asigna una. Al elegir ese cliente en una venta, los precios pasan a los de su lista.
- En **Vender**, dentro del carrito, **Lista** elige otra para esa venta.

Estas opciones aparecen cuando hay más de una lista vigente. En el menú ⋮ de una lista, **Archivar** la retira: sus clientes vuelven a **Lista general** y deja de ofrecerse en ventas nuevas. **Reactivar** la recupera.

## Moneda de referencia

**Moneda de referencia**, en **Negocio**, sirve para pensar precios desde otra moneda. No cambia la moneda del negocio: ventas, costos y saldos siguen en ella.

1. **Elegir moneda** y sus **Decimales**.
2. **Registrar cotización**: la **Cotización**, la **Fecha** y una **Nota** con su origen. Se escribe a mano: {app} no la descarga. Cada cotización nueva se suma al **Historial** sin borrar las anteriores.
3. En cada producto, el **Precio de referencia** guarda su precio en esa moneda.
4. **Repreciar con esta cotización** prepara la actualización de precios para revisarla antes de aplicarla. Ningún precio cambia solo.
