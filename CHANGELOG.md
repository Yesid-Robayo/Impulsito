# Historial de cambios

**Español** · [English](CHANGELOG.en.md)

Lo que trajo y arregló cada versión de Impulsito, de la más nueva a la más antigua. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), y las versiones siguen [Versionado Semántico](https://semver.org/lang/es/).
Las versiones publicadas tienen su APK, sus notas y su SHA-256 en [Releases](https://github.com/Yesid-Robayo/Impulsito/releases).

Cuando sale una versión nueva, se añade arriba con el mismo contenido que sus notas.

## [1.0.8] - 2026-11-18

### Agregado

- **Lo que no existe se crea al registrar:** un producto dicho que no está en el catálogo, y sin parecidos, aparece en la tarjeta como
  **Nuevo** y se crea al tocar **Registrar**, en la misma transacción que la venta, la compra, el conteo o la producción. Si algo se
  rechaza, no queda ni el producto. Con parecidos se elige como antes.
- **Producir algo nuevo:** «hice un pastel con 200 g de harina» crea el pastel con eso como su receta y lo produce. «hice un pastel» y
  después «lleva 200 g de harina» también.
- **La unidad se cambia en cada línea:** tocando la línea, entre las unidades del producto y las de su clase.
- **La medida de la libra, la onza y el galón:** cambian según el lugar, así que la app no la supone. La primera vez que se dicen gramos de
  algo que se lleva por libra, la línea pregunta cuánto pesa una libra, con los valores habituales u **Otro valor**, y convierte en el
  acto. Al registrar, la medida queda en el producto y desde ahí convierte sola.

### Corregido

- **Miles con espacio:** «gasté 200 000 en el arriendo» queda en 200 000, también con los espacios que pone el dictado.
- **Una venta sin precio:** un producto sin precio, como uno creado al producirlo, pide el precio en vez de venderse en cero.
- **Combos en una receta:** un insumo dicho que la receta trae dentro de un combo ya no se cuenta dos veces.
- **Frases en inglés y portugués:** un cliente que pagó lo que se llevó es una venta, y un pago con tarjeta ya no se lee como una
  producción.

## [1.0.7] - 2026-11-18

### Agregado

- **Producir diciendo lo que lleva:** «hice un pastel con 200 g de harina y 3 huevos» produce con eso. Sin receta, la tarjeta pide qué
  lleva y, al registrar, lo guarda como receta por unidad y produce en una sola transacción. Con receta, lo dicho vale solo esa vez; la
  tarjeta muestra la diferencia y **Actualizar la receta** también la cambia. Gramos y kilos, mililitros y litros se convierten solos.
- **Avisa las versiones nuevas:** una vez al día, con internet y sin enviar datos, la app consulta el aviso de versión. Si hay una
  nueva, llega una notificación, una vez por versión, e **Inicio** lo dice hasta instalarla. Nada se descarga sin tocar **Actualizar**.
  Se apaga en **Actualizaciones y verificación de APK**.
- **Notificaciones al empezar:** al terminar la configuración inicial, una hoja explica para qué sirven y Android pide el permiso. A
  quien ya usaba la app se le pregunta una sola vez.
- **Operación en la tarjeta:** Venta, Compra, Conteo y Producción arriba de la tarjeta, sin perder las líneas al cambiar.

### Cambiado

- **La tarjeta suma y no borra:** con la tarjeta abierta, lo dicho suma o corrige; sin verbo, sigue la operación de la tarjeta. Otra
  operación pregunta si empezar otra tarjeta. El tipo o la categoría de un producto que ya existe ofrecen abrir el producto, sin tocar
  la tarjeta.

### Corregido

- **Conteos y producciones de varios productos:** se registran completos, en una sola transacción, en lugar de rechazarse.

## [1.0.6] - 2026-11-18

### Agregado

- **Gastos en la barra:** la barra de abajo es **Inicio**, **Vender**, **Gastos**, **Productos** y **Números**. **Gastos** abre los gastos
  del mes con **Por categoría** arriba: cada categoría con su monto y su parte del mes, y al tocarla filtra la lista.
- **Producto nuevo por frase:** el tipo («es un insumo», «es para vender», «es un servicio») y la categoría («en la categoría materias
  primas») llegan a la tarjeta. Si la categoría no existe, se crea junto con el producto.

### Cambiado

- **Decir, abajo a la derecha:** el micrófono sale de la barra y queda como botón redondo en las pestañas principales. En tableta sigue
  arriba del riel.
- **Entiende más:** muchas más formas de decir cada operación, verbos, montos («2 mil 500», «son 6 mil», «50 000»), medidas («un kilo
  doscientos», «2 tandas de 20»), pagos («todo en efectivo», «me las debe») y dos oraciones en una frase. Las preguntas, los planes, las
  negaciones, las devoluciones y los retiros de caja no registran nada.
- **Corregir conserva lo demás:** con la tarjeta abierta, lo dicho cambia solo lo que nombra: el producto, la cantidad, el precio, el pago,
  el motivo de un ajuste o la operación entre venta y compra. Una frase que no corrige nada ya no borra la tarjeta.

### Corregido

- **Decir no escuchaba en algunos teléfonos:** el servicio de voz recibía el idioma sin país y se cerraba al instante. Ahora se pide con
  país, se usa el idioma que el teléfono tiene descargado y se prueba cada servicio de voz instalado.
- **Una oración que describe ya no inventa un producto:** «esto es un insumo» no se vuelve otra línea.

## [1.0.5] - 2026-11-17

### Cambiado

- **Decir, al centro:** el botón de abajo al centro es **Decir**. Escucha de una vez con una pantalla propia, que muestra la frase
  mientras se dice, y abre la tarjeta. Usa el servicio de voz del teléfono sin su ventana, y la primera vez pide el micrófono, después
  de explicar para qué. Se apaga en **Ajustes**; apagado, o sin permiso, queda el teclado.
- **La barra de abajo:** **Inicio**, **Vender**, **Decir**, **Productos** y **Números**. **Registros** se abre desde **Inicio**, y vender a
  mano también está en **Vender a mano** dentro de **Decir**, en el widget y en el atajo del ícono.
- **Cantidades:** una cantidad entera se muestra sin decimales, **10 kg** y no 10,000 kg; una que no es entera conserva sus tres
  decimales.

### Corregido

- **Montos dictados:** «50,000» con la coma de miles del dictado son cincuenta mil, no cincuenta.
- **¿No funciona el micrófono?**, en **Decir**, abre los pasos para hacerlo funcionar.

## [1.0.4] - 2026-11-17

### Agregado

- **Decir o escribir:** una frase dicha o escrita, como «vendí 3 panes y 2 jugos en efectivo», se muestra en una tarjeta con cada
  producto, su monto y el total, y se registra solo al tocar **Registrar**. Entiende ventas, compras, gastos, producción, ajustes de
  stock, abonos, pagos a proveedores, recetas, productos nuevos y cambios de costo y precio, en español, inglés y portugués. Lo dudoso
  se pregunta, y la tarjeta se corrige tocando una línea o con una frase corta como «no, eran 3». Se abre desde **Inicio**, el widget y
  el atajo del ícono. Funciona sin internet: se dicta con el micrófono del teclado.
- **Voz directa, opcional:** en **Ajustes**, apagada de inicio, **Decir** puede abrir el servicio de voz del teléfono. La app nunca
  pide el micrófono.

### Corregido

- **Editar producto:** con movimientos, el costo dice que se actualiza con cada compra y ofrece **Corregir costo**. Antes el campo
  quedaba bloqueado sin decir dónde se cambia.

## [1.0.3] - 2026-11-17

### Agregado

- **Ayuda completa:** 25 artículos nuevos, entre ellos recetas y producción, compras y proveedores, cuentas por pagar, gastos,
  reposición, categorías, variantes, ubicaciones, escáner y etiquetas, listas de precios, Números, metas, registros, preferencias,
  widget y el centro **Negocio**. La ayuda se agrupa según lo que se quiere hacer, y el botón de ayuda de cada pantalla abre su
  artículo.

### Corregido

- **Producción** sin recetas: **Cómo crear una receta** explica los pasos. Antes abría los combos, que no se producen.
- **Productos:** editar un producto ya no le quita el proveedor habitual que le dio una importación.
- **Ayuda:** dos rótulos que ya no coincidían con la app.

## [1.0.2] - 2026-11-17

### Agregado

- **Actualizar con un toque:** cuando **Buscar actualización** encuentra una versión nueva, **Actualizar** la baja, comprueba que sea
  la anunciada en el aviso firmado y que lleve la firma de la app, y Android pide confirmar. La primera vez, Android pide permitir que
  Impulsito instale sus actualizaciones.
- **Novedades,** en **Actualizaciones y verificación de APK**: lo que trajo cada versión, sin internet.
- **Producir para reponer:** un producto con receta ofrece **Producir** en el inventario, la reposición sugerida y su detalle, y abre
  la producción con ese producto elegido.
- **Costo total del stock inicial:** al crear un producto con stock inicial, se puede escribir el total pagado en vez del costo por
  unidad. El valor del stock queda exacto, también en productos baratos por gramos.

### Corregido

- **Proveedores,** en el detalle de un producto sin compras: el aviso ya no queda pegado al borde.

## [1.0.1] - 2026-11-17

### Corregido

- **Copias:** si el sistema detenía una copia a mitad o la red la hacía esperar, la copia podía quedar en la nube sin sus
  metadatos. Ahora se conservan hasta que la copia termine.
- **Copias con WebDAV:** la prueba de conexión avisa cuando un paso tarda, se detiene tras 5 minutos sin guardar nada y ofrece
  reintentar.
- **Números:** la gráfica ya no elige un día que todavía no llega como el menor del período.
- **Textos:** con la nube del teléfono, la ayuda y los pasos nombran el botón **Guardar en Drive**. La hora de la última copia ya no
  queda con doble punto.

## [1.0.0] - 2026-11-16

Primera versión pública. Es gratis y se ofrece tal cual, **sin garantía**. No es un programa de contabilidad ni emite facturas: sus
comprobantes son de uso interno.

### Ventas y clientes

- **Ventas en pocos toques,** con varios métodos de pago por venta, comisiones y neto recibido, cambio, propinas, descuentos y ventas
  en espera.
- **Anulaciones y devoluciones** con registros compensatorios: nada se borra, y una devolución conserva el costo original.
- **Clientes y crédito:** ventas a crédito, abonos, extractos, saldo a favor y puntos. Solo se pide el nombre.
- **Sesiones de caja** con lo esperado frente a lo contado, y conteo por denominación.

### Stock y compras

- **Productos** con unidades y conversiones, servicios, variantes, combos y recetas, fotos y campos propios.
- **Escáner de códigos** con la cámara o con un lector externo, y códigos y etiquetas propios.
- **El stock como libro de movimientos,** con lotes y vencimientos, números de serie, ubicaciones y transferencias, producción,
  reposición sugerida y conteo a ciegas.
- **Compras y proveedores** con costo promedio y de reposición, compras a plazo, pagos parciales, vencimientos y devoluciones al
  proveedor.
- **Precios:** listas de precios, actualización masiva, alertas de margen y una moneda de referencia con la cotización que tú
  escribes.

### Gastos y reportes

- **Gastos y gastos recurrentes,** y cuentas de dinero con retiros, aportes y transferencias.
- **Ganancia y margen:** ganancia en ventas y ganancia final del mes, e impuestos con las tasas que tú escribes.
- **Reportes:** resumen de gestión, flujo de caja proyectado, punto de equilibrio, metas, análisis de productos y clientes, resumen
  diario y comparación en términos reales.
- **Importar** productos desde CSV o una hoja de cálculo, y **exportar** todo a CSV o `.xlsx`.

### Documentos

- **Comprobantes de venta** en PDF o imagen, **cotizaciones**, **pedidos y entregas** y un **catálogo para compartir**, todos
  documentos internos.
- **Impresión** en impresoras térmicas Bluetooth emparejadas o con el servicio de impresión del sistema.

### Copias de seguridad

- **Copias automáticas y comprobadas** en un servicio WebDAV que elijas, como Koofr: **Todo guardado** solo aparece después de volver
  a leer la copia.
- **Copias en la nube del teléfono,** hoy Google Drive, guardadas a mano con **Guardar en Drive** mientras la app está abierta.
- **Nunca se sobrescribe la última copia buena,** con una segunda copia en el celular y **Exportar una copia completa** a un archivo.
- **Restauración cuidadosa** que comprueba cada copia antes de reemplazar nada y puede volver a como estaba antes, y **Probar
  restauración**, que no toca tus datos.
- **Cifrado opcional,** apagado por defecto, con código de recuperación y un formato abierto con herramienta de rescate en
  [`tools/backup-format`](tools/backup-format).

### Privacidad y seguridad

- **Sin cuenta, sin servidores, sin anuncios, sin analítica y sin rastreo.** Funciona completa sin internet.
- **Bloqueo** con la huella, el rostro o el PIN del teléfono, **modo discreto** para ocultar los montos, un **registro de auditoría**,
  una pantalla con qué datos hay y adónde van, y **Borrar mis datos**.
- **Verificar un APK** compara el certificado de un archivo antes de instalarlo, y **Buscar actualización** lee un aviso de versión
  firmado sin enviar ningún dato. La app nunca instala nada por su cuenta.

### Idiomas

- **Español, inglés y portugués,** con cualquier moneda, decimales y formato de fechas y números: no se asume nada de tu país.
- **Widget en la pantalla de inicio, mosaico de ajustes rápidos, atajos, diseño para tableta,** una demostración con datos de
  ejemplo, ayuda dentro de la app, tema claro y oscuro y nueve paletas de colores.

[1.0.0]: https://github.com/Yesid-Robayo/Impulsito/releases/tag/v1.0.0
