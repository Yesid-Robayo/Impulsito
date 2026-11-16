# Security · Seguridad

[English](#english) · [Español](#español)

## English

### How to report a security problem

If you find a security flaw (for example, a way to make Impulsito send business data anywhere other than the backup destination
you chose, to read the WebDAV password or the recovery code, to install an APK that isn't the official one without the app warning
you, to accept a forged version notice, or to make a crafted backup do anything other than be rejected when restored), **do not post
it in an *issue***. Use GitHub's private report:

1. Open [Report a vulnerability](https://github.com/Yesid-Robayo/Impulsito/security/advisories/new) (the repository's **Security**
   tab).
2. Describe what happens, how to reproduce it and with which version.
3. Use made-up data. Never attach a real backup, database or screenshot with a real business's data.

Only the author will see it. There is **no bug bounty** and **no promised response time**: it's made by one person. Once a problem is
fixed, a new version is published.

### Supported versions

Only the latest version published in [Releases](https://github.com/Yesid-Robayo/Impulsito/releases) receives fixes.

### In scope

- **The app:** how it stores, backs up, encrypts, restores, exports and shares data, how it talks to the WebDAV service, and what it
  writes to logs.
- **Signing:** the authenticity of the APKs published in Releases, the release certificate and the **Check an APK** feature.
- **The version notice:** `version.json` and its signature at `https://yesid-robayo.github.io/Impulsito/`, and how the app reads it.
- **The rescue tool** in [`tools/backup-format`](tools/backup-format).

Out of scope: flaws in the WebDAV service, Google Drive, Android itself or the phone, and attacks that need the phone already
unlocked in someone else's hands.

### What Impulsito protects

- **No servers and no third-party network.** The app only connects to the WebDAV service you chose and, when you tap **Check for
  update**, to the version notice, which receives no data. No analytics, ads or crash reporting.
- **HTTPS only,** with the system's certificate authorities only: no cleartext traffic and no user-installed certificates.
- **The WebDAV password** is stored sealed with a key from the Android Keystore, and never goes into the database, a backup or a log.
- **Every restored backup is untrusted,** even one the app made: size, checksum, decryption, structure and data are checked, in that
  order, before anything is replaced.
- **Optional backup encryption** with [Tink](https://developers.google.com/tink). The recovery code is never stored in the cloud, a
  backup, a log or the clipboard, and it's shown with screenshots blocked.
- **Logs carry no business data:** no amounts, names, notes, addresses or credentials.
- **Android's backup and device transfer are off,** so data only leaves the phone through the destination you chose.
- **Updates can't be pushed.** The version notice is signed with its own key, kept offline and different from the signing key. It
  only informs: it never blocks the app, downloads or installs anything.

### What it does not protect

- **The database on the phone isn't encrypted.** It relies on the phone's own protection, and on the optional app lock.
- **Backups are not encrypted by default.** Anyone who gets into your cloud account can read them unless you turn encryption on.
- **The first installation** can only be checked by hand, against the fingerprint in the README. After that, Android rejects any
  update signed with a different key.
- **If you lose the recovery code,** encrypted backups can't be opened by anyone, including the author.

## Español

### Cómo avisar de un problema de seguridad

Si encuentras un fallo de seguridad (por ejemplo, una forma de hacer que Impulsito envíe datos del negocio a otro lugar que no sea
el destino de copias que elegiste, de leer la contraseña de WebDAV o el código de recuperación, de instalar un APK que no es el oficial
sin que la app avise, de que acepte un aviso de versión falsificado, o de que una copia manipulada haga algo distinto de ser rechazada
al restaurar), **no lo publiques en un *issue***. Usa el aviso privado de GitHub:

1. Abre [Report a vulnerability](https://github.com/Yesid-Robayo/Impulsito/security/advisories/new) (pestaña **Security** del
   repositorio).
2. Describe qué pasa, cómo reproducirlo y con qué versión.
3. Usa datos inventados. Nunca adjuntes una copia, una base ni una captura reales con datos de un negocio.

Solo lo verá el autor. **No hay recompensas** ni **tiempo de respuesta prometido**: lo hace una sola persona. Cuando un problema se
arregla, se publica una versión nueva.

### Versiones con soporte

Solo la última versión publicada en [Releases](https://github.com/Yesid-Robayo/Impulsito/releases) recibe arreglos.

### Qué cubre

- **La app:** cómo guarda, respalda, cifra, restaura, exporta y comparte los datos, cómo habla con el servicio WebDAV y qué escribe
  en los registros.
- **La firma:** la autenticidad de los APK publicados en Releases, el certificado de release y la función **Verificar un APK**.
- **El aviso de versión:** `version.json` y su firma en `https://yesid-robayo.github.io/Impulsito/`, y cómo lo lee la app.
- **La herramienta de rescate** de [`tools/backup-format`](tools/backup-format).

Fuera de alcance: fallos del servicio WebDAV, de Google Drive, de Android o del teléfono, y ataques que necesitan el teléfono ya
desbloqueado en manos de otra persona.

### Qué protege Impulsito

- **Sin servidores ni red de terceros.** La app solo se conecta al servicio WebDAV que elegiste y, cuando tocas **Buscar
  actualización**, al aviso de versión, que no recibe ningún dato. Sin analítica, anuncios ni reportes de fallos.
- **Solo HTTPS,** y solo con las autoridades de certificación del sistema: sin tráfico en claro y sin certificados instalados por el
  usuario.
- **La contraseña de WebDAV** se guarda sellada con una clave del Android Keystore, y nunca entra en la base, en una copia ni en un
  registro.
- **Toda copia restaurada es no confiable,** aunque la haya hecho la app: se comprueban tamaño, suma, descifrado, estructura y datos,
  en ese orden, antes de reemplazar nada.
- **Cifrado opcional de las copias** con [Tink](https://developers.google.com/tink). El código de recuperación nunca se guarda en la
  nube, en una copia, en un registro ni en el portapapeles, y se muestra con las capturas de pantalla bloqueadas.
- **Los registros no llevan datos del negocio:** ni montos, ni nombres, ni notas, ni direcciones, ni credenciales.
- **El respaldo y la transferencia de Android están apagados,** así que los datos solo salen del teléfono por el destino que elegiste.
- **Nadie puede forzar una actualización.** El aviso de versión se firma con su propia clave, guardada sin conexión y distinta de la
  de firma. Solo informa: nunca bloquea la app, ni descarga ni instala nada.

### Lo que no protege

- **La base del teléfono no está cifrada.** Depende de la protección del propio teléfono, y del bloqueo opcional de la app.
- **Las copias no se cifran por defecto.** Quien entre en tu cuenta de la nube puede leerlas, salvo que actives el cifrado.
- **La primera instalación** solo se puede comprobar a mano, con la huella del README. Después, Android rechaza cualquier
  actualización firmada con otra clave.
- **Si pierdes el código de recuperación,** nadie puede abrir las copias cifradas, ni siquiera el autor.
