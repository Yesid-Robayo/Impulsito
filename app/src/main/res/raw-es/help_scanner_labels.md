Con un código de barras en cada producto, vender, comprar y contar se hace apuntando la cámara, sin buscar por nombre. Si el producto no trae código, {app} crea uno y lo imprime en una etiqueta.

## Darle un código a un producto

- Al editar el producto, en **Más detalles**, **Código de barras**: escribirlo o leerlo con la cámara, con el botón del campo.
- **Generar código** crea un código propio, único, para un producto guardado que no tiene ninguno.
- Un código que ya usa otro producto no se guarda: el campo dice cuál lo tiene.
- Una conversión, como una caja, puede tener su propio código: al leerlo en una venta, la línea entra en esa unidad.

## Escanear

El escáner se abre con el botón de escanear de **Vender**, de una compra, de un conteo, de **Productos** y del inventario.

- En una venta, una compra o un conteo, la lectura es continua: cada código leído se suma y la cámara sigue abierta hasta tocar **Terminar**. **Último** muestra el código leído.
- Un producto quieto delante de la cámara se suma una sola vez. Para sumarlo otra vez, se saca del cuadro y se vuelve a mostrar.
- En un conteo, cada lectura suma una unidad. Si la unidad del producto admite decimales, como el peso, o si tiene varios lotes, lo busca para escribir la cantidad.
- En **Productos** y en el inventario, leer un código abre el producto.

Un código que ningún producto tiene abre **Código sin producto**, con **Crear producto** o **Asignar a un producto**. Uno de un producto archivado ofrece **Reactivar**. Con poca luz, la linterna ayuda.

## La cámara

El permiso de la cámara se pide solo al abrir el escáner, con **Permitir cámara**. La cámara se usa solo para leer códigos, y solo con esa pantalla abierta: ninguna imagen se guarda ni sale del teléfono. Sin permiso, o si la cámara no abre, **Escribir el código** sirve igual.

## Etiquetas imprimibles

En **Negocio**, **Etiquetas imprimibles**, o en **Productos**, ⋮ → **Etiquetas imprimibles**:

1. **Qué productos**: **Elegir**, por **Categoría** o **Solo lo que cambió de precio** **Desde** una fecha. Cada producto puede llevar varias copias.
2. **Plantilla**: **Nombre y precio**, **Nombre, precio y código** o **Solo código**.
3. **Tamaño**: **58 mm** u **80 mm** para una impresora térmica emparejada por Bluetooth, o **Hoja PDF**, que con **Crear PDF** abre la impresión del teléfono.
4. Imprimir. Una vista previa muestra la primera etiqueta.

Si la plantilla lleva código y algunos productos no tienen, **Generar códigos** los crea y los guarda en cada producto. Si lleva precio, los productos sin precio quedan fuera. Sin impresora, **Configurar impresora** la elige entre las emparejadas.
