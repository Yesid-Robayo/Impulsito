# Historial de cambios

**Español** · [English](CHANGELOG.en.md)

Lo que trajo y arregló cada versión de Impulsito, de la más nueva a la más antigua. El formato sigue
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), y las versiones siguen [Versionado Semántico](https://semver.org/lang/es/).
Las versiones publicadas tienen su APK, sus notas y su SHA-256 en [Releases](https://github.com/Yesid-Robayo/Impulsito/releases).

Cuando sale una versión nueva, se añade arriba con el mismo contenido que sus notas.

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
