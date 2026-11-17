El **Registro de auditoría** muestra qué pasó con los datos y cuándo: exportaciones, restauraciones y cambios de seguridad. Está en **Ajustes**, **Seguridad**, **Registro de auditoría**.

## Qué registra

Cada evento lleva la hora, qué pasó y su resultado, **Correcto** o **Fallo**:

- **Exportación a CSV** y **Copia completa exportada**.
- **Restauración** y **Restauración rechazada**.
- **Nube desconectada** y **Cambio de nube**.
- **Bloqueo de la app cambiado** y **Autenticación fallida**.
- **Cifrado de copias cambiado** y **Código de recuperación visto**.
- **Moneda corregida**.
- El borrado completo de los datos del teléfono.

## Qué no registra

- Montos, nombres de productos, clientes o proveedores, ni otro contenido del negocio.
- Quién lo hizo: {app} es para una sola persona y un solo teléfono.
- Ventas, compras, gastos o cambios de precio: esos quedan en su propio historial, que tampoco se borra.

## Cómo consultarlo

Los eventos van agrupados por día, del más reciente al más antiguo. **Todos** muestra cada evento, y **Solo fallos**, solo los que fallaron. Si no hay ninguno, **Quitar filtros** vuelve a la lista completa.

Un **Fallo** no siempre es un problema grave: una copia rechazada al restaurar o una verificación de identidad que no pasó quedan anotadas para saber que ocurrieron.

## Nada se edita

El registro es de solo lectura: ningún evento se edita, se borra a mano ni se exporta. Se conservan los últimos 5.000 eventos: al pasar de ese número, los más viejos dejan lugar a los nuevos.

El [borrado completo de los datos](help_delete_data) es lo último que se anota antes de borrar el teléfono: después, el registro se va con todo lo demás.
