# Historial de cambios

**Español** · [English](CHANGELOG.en.md)

Lo que trajo y arregló cada versión de Impulsito, de la más nueva a la más antigua. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), y las versiones siguen [Versionado Semántico](https://semver.org/lang/es/).
Las versiones publicadas tienen su APK, sus notas y su SHA-256 en [Releases](https://github.com/Yesid-Robayo/Impulsito/releases).

Cuando sale una versión nueva, se añade arriba con el mismo contenido que sus notas.

## [1.0.5] - 2026-11-17

### Cambiado

- **Decir, al centro:** el botón de abajo al centro ahora es **Decir**, para registrar con una frase desde cualquier pestaña. Vender
  a mano sigue en **Inicio**, en **Vender a mano** dentro de **Decir**, en el widget y en el atajo del ícono.

### Agregado

- **¿No funciona el micrófono?**, en **Decir**: los pasos para activar el dictado del teclado cuando dice que la voz no está
  disponible, también en la ayuda.

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
