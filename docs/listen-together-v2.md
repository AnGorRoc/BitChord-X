# Listen Together v2: dispositivos estilo Spotify Connect + party por cuenta

Estado: **plan**, nada implementado. Sustituye a "Mis dispositivos" (sala personal con botón) y a la
pantalla actual de Listen Together como entrada principal.

---

## 1. Qué cambia conceptualmente

| Hoy | v2 |
|---|---|
| "Mis dispositivos" es una sala a la que se entra con un botón. El primero suena, el resto controla. | Los dispositivos de la cuenta **siempre se ven entre sí** al abrir la app. Nadie controla a nadie hasta que tocas otro dispositivo. |
| La sala personal tiene **una sola reproducción compartida** (una cola, un estado). | Cada dispositivo tiene **su propia reproducción**. El canal de cuenta solo transporta presencia ("qué suena en cada uno") y órdenes dirigidas a un dispositivo. Es el modelo de Spotify Connect. |
| En una party, cada dispositivo es un miembro aparte (dos de tu cuenta = dos personas, dos plazas, dos hosts posibles). | En una party, **la persona es la cuenta**. Sus dispositivos aparecen agrupados bajo un nombre y una foto; cualquiera de ellos puede mandar con los permisos de esa persona. |
| La cola de la party se recorta a 25 próximas. | La party sincroniza **la cola completa** (límite que anuncia el servidor; 25 solo con el servidor original). |
| La cola no dice quién añadió cada canción. | Cada canción lleva **quién la añadió** (foto en la esquina inferior derecha de la portada; icono de AutoPlay si vino de ahí). |
| Crear party pide nombre; el puente de Spotify se elige al crear. | Crear party es **un toque**: usa el nombre ya configurado. El puente de Spotify se activa o desactiva **en cualquier momento** desde la party. |
| La pantalla de Listen Together es la entrada principal. | Es **solo configuración**. La entrada principal es el modal **Conectar** del reproductor. |

---

## 2. Reglas de comportamiento (las que el código tiene que cumplir)

1. **Libres por defecto.** Si no tocas nada, cada dispositivo reproduce lo suyo. Estar "visible" no
   silencia, no sincroniza y no cambia nada.
2. **Tocar otro dispositivo = mandar el sonido allí.** El dispositivo donde tocas pasa a ser
   **control remoto** de ese otro: sus controles, cola, barra de progreso y volumen actúan sobre el de
   destino; el sonido local se pausa.
3. **Prioridad del que suena.** Al conectarte a un dispositivo:
   - si el destino **está sonando**, sigue con lo suyo; tú solo pasas a controlarlo;
   - si el destino **está en pausa o inactivo**, se lleva tu canción, posición y cola, y empieza a
     sonar allí (traspaso, como Spotify).
   - Acción secundaria en el menú `⋯` del dispositivo: **"Traer lo que suena aquí"** (fuerza el
     traspaso en sentido contrario) y **"Llevar lo mío allí"** (fuerza el traspaso aunque el destino
     suene). Así la regla por defecto no deja a nadie sin salida.
4. **Volver.** En el modo control aparece "Reproduciendo en IOP" en el reproductor y en el mini
   reproductor. Tocar **"Este teléfono"** en el modal trae el sonido de vuelta (traspaso inverso: la
   canción, posición y cola del remoto pasan aquí y el remoto se pausa).
5. **Misma cuenta = misma persona**, tanto en el canal de dispositivos como en la party.
6. **En una party**, los dispositivos de una cuenta cuentan como **una persona**: un nombre, una foto,
   una plaza. Cualquiera de ellos puede añadir, saltar o (si la persona es host) administrar. Cada
   dispositivo decide aparte si suena o solo controla (la opción "Reproducir en este dispositivo" ya
   existente).

---

## 3. Zonas de la interfaz

### 3.1 Punto de entrada (reproductor)

- La pastilla actual `OutputPartyPill` (`sharedUi/.../PlayerControls.kt`) mantiene sus dos mitades:
  - **izquierda (salida de audio)**: sin cambios (Bluetooth, auriculares, Cast, reloj);
  - **derecha (party/personas)**: abre el **modal Conectar** siempre, esté o no en party. Ya no
    navega a Ajustes → Listen Together.
- El icono de la mitad derecha cambia a "dispositivos" cuando otro dispositivo de la cuenta está
  visible, y se resalta en verde (acento) cuando estás controlando otro o estás en party.
- El subtítulo bajo los controles (`OutputCaption`) muestra, por orden de prioridad:
  "Reproduciendo en IOP" › "Party de Ana · 4" › la salida de audio actual.
- **Mini reproductor**: franja fina de acento con "Reproduciendo en IOP" mientras controlas otro
  dispositivo (como la barra verde de Spotify).

### 3.2 Modal "Conectar" (hoja inferior en Android, popover/diálogo anclado en PC)

```
┌──────────────────────────────────────────┐
│ Conectar                                  │
│ ┌──────────────────────────────────────┐ │
│ │ IOP            [Muy alta] ›    [💻]  │ │  ← tarjeta del dispositivo que SUENA
│ │ TUSSI — LAJA, Anuel AA (acento)      │ │    (si es remoto: barra de volumen)
│ │ 🔈 ━━━━━━━━━━━━━━━━━━━━━━━━━━━ 🔊   │ │
│ └──────────────────────────────────────┘ │
│ Tus dispositivos                          │
│ 📱 Este teléfono                          │  ← si el que suena es otro
│ 🖥  Escritorio-casa   Sonando: Otra canc. ⋯│
│ ·· Tablet          Visto hace 2 h      ⋯ │  ← inactivo, atenuado
│                                           │
│ Party                                     │
│  (fuera)  [ Iniciar party ] [ Unirse ]    │
│  (dentro) 👤👤👤 Party de Ana · 3 personas │
│           [Invitar] [Ver party ›]         │
│                                           │
│ Ajustes de Listen Together ›              │
└──────────────────────────────────────────┘
```

- **Tarjeta principal**: el dispositivo que suena *para ti* (el local, o el remoto que controlas).
  Nombre, calidad (como hoy), canción en color de acento, icono del tipo de dispositivo. Si es
  remoto, barra de volumen remota y `›` que abre sus detalles (renombrar si es este, desconectar).
- **Tus dispositivos**: solo los de tu cuenta. Cada fila: icono (teléfono/PC/tablet/TV), nombre del
  dispositivo, y en pequeño qué suena allí o "Inactivo". Tocar = regla 2/3. `⋯` = traer/llevar,
  renombrar (solo el propio).
- Sin dispositivos: una línea "Abre BitChord con tu cuenta en otro dispositivo para verlo aquí".
  No hay botón de "iniciar" nada.
- **Party**: fuera de una party, dos botones al pie (como "Start a Jam" de Spotify). "Iniciar party"
  la crea al instante, sin preguntar nada, y muestra la hoja de invitar. "Unirse" abre pegar
  enlace / código / QR. Dentro de una party: fila resumen con avatares, "Invitar", "Ver party".
- **Pie**: enlace a la configuración.

### 3.3 Hoja "Party" (desde "Ver party" o tocando el subtítulo en party)

Reemplaza a `ListenTogetherMembersSheet` + la parte "en party" de la pantalla actual.

1. Cabecera: "Party de Ana", código, botón **Invitar** (enlace/QR; pestaña Spotify si el puente está
   activo).
2. **Personas** (no dispositivos): avatar, nombre, corona si es host, "2 dispositivos" y un icono de
   altavoz tachado si ninguno de sus dispositivos suena. Expandible para ver sus dispositivos.
   Menú del host: expulsar (a la persona entera).
3. **Este dispositivo**: interruptor "Reproducir en este dispositivo".
4. **Puente de Spotify** (solo host, solo si hay cuenta de Spotify vinculada): interruptor
   "Abrir también como Spotify Jam". Se puede activar o desactivar en cualquier momento.
5. **Opciones de host** (plegadas): solo el host controla, AutoPlay, tamaño máximo.
6. **Salir de la party**.

La **cola** no se duplica aquí: es la cola normal del reproductor, que en party muestra la de la party
con el avatar de quien añadió cada canción.

### 3.4 Cola (Android y PC)

- Portada con **avatar circular de ~18 dp** en la esquina inferior derecha, con borde del color de
  fondo. Canciones de AutoPlay: icono de AutoPlay en el mismo sitio. Canciones tuyas: tu avatar
  (para que se vea coherente) o nada — propuesta: nada en lo tuyo fuera de party, avatar siempre en
  party.
- Toque largo en el avatar: "Añadida por Ana".

### 3.5 Pantalla de configuración (la actual, reducida)

Solo ajustes, sin crear/unirse/estado de party:

- **Tu nombre en las parties** (por defecto el de la cuenta).
- **Nombre de este dispositivo** (por defecto: modelo en Android, nombre del equipo en PC — el "IOP"
  de las capturas).
- **Visible para mis otros dispositivos** (sí/no).
- **Permitir que mis otros dispositivos controlen este** (sí/no).
- **Servidor de party**: por defecto / personalizado + estado (funciones disponibles, latencia).
- **Spotify**: cuenta vinculada, nivel (Premium/Free).
- **Botón de party en el reproductor** (el ajuste que ya existe).
- Avanzado: tamaño de party por defecto, diagnóstico.

---

## 4. Sistemas

### 4.1 Canal de cuenta (servidor) — nuevo, sustituye a la sala personal

Una conexión WebSocket por dispositivo, abierta mientras la app está en primer plano **o**
reproduciendo (servicio de reproducción en Android). No es una Party: no tiene cola ni estado
compartido.

- `POST /api/account/connect` `{accountKey, deviceId, deviceName, deviceType}` → token. El
  `accountKey` es el mismo secreto de cuenta que ya usa `DeviceGroup` (playlist privada en YouTube
  Music), hasheado. Es la prueba de que es la misma cuenta.
- Frames servidor → cliente:
  - `devices`: lista de dispositivos de la cuenta `{deviceId, name, type, connected, lastSeenMs,
    controlledBy?, nowPlaying?: {track, isPlaying, positionMs, anchorMs, volume}}`.
- Frames cliente → servidor:
  - `nowPlaying`: el dispositivo publica su estado (con antirrebote, y al cambiar de pista o pausar).
  - `command {to, action, ...}`: reenviado solo al destino. Acciones: `play`, `pause`, `seek`,
    `next`, `previous`, `setVolume`, `playQueue {tracks, index, positionMs}` (traspaso),
    `enqueue`, `release` (devuélveme el sonido).
  - `attach {to}` / `detach`: empiezo/dejo de controlar ese dispositivo (para que los demás vean
    "controlado por Teléfono").
- Feature en `/api/info`: `accountDevices`. Sin ella, el modal Conectar muestra solo "Este
  dispositivo" y la sección Party.
- Memoria: un mapa `accountKey → dispositivos`, caduca al desconectarse todos (con gracia de unos
  minutos). Nada persiste en disco.

### 4.2 Traspaso (cliente)

`DeviceHandoff` en `:shared`:
- `connectTo(target)`: aplica la regla 3. Si hay traspaso, envía `playQueue` con la cola local
  completa (máximo razonable, p. ej. 200 pistas + índice + posición) y pausa local al recibir el
  `nowPlaying` del destino sonando (no antes: si falla, no se queda nadie en silencio).
- `release()`: pide al remoto su cola y posición, la carga localmente, reproduce y envía `pause` al
  remoto.
- Mientras controlas: el `PlayerConnection` (Android) y `DesktopApp` (PC) redirigen sus acciones
  igual que hoy hace `routedToRemote` con la sala personal; esa parte se reutiliza.

### 4.3 Party por persona (servidor)

- `Member` gana `AccountKey` (opcional, hash). Al unirse con un `accountKey` que ya está en la party,
  el nuevo dispositivo **hereda** el rol de host y no consume plaza.
- `members` frame: se añade `personId` (hash corto del accountKey, o el memberId si no hay) para que
  los clientes agrupen. Sigue siendo una lista de dispositivos para no romper al cliente original.
- `party_full` cuenta personas distintas, no dispositivos.
- `kick` expulsa a todos los dispositivos de esa persona. `hostOnlyControl` deja controlar a todos
  los dispositivos del host.
- Clientes del BitChord original: no mandan `accountKey`, cada dispositivo es una persona (lo de
  siempre).

### 4.4 Quién añadió cada canción

- `Track` gana `addedBy` (memberId) y el servidor lo **sella** en `setQueue`, `queueAdd`, `setTrack`
  (no se confía en lo que diga el cliente). AutoPlay mantiene `fromAutoplay`.
- El cliente resuelve `addedBy → persona → avatar` con el frame `members`. Si la persona se fue, se
  guarda nombre y avatar en el propio track (`addedByName`, `addedByAvatar`) para no perderlo.
- Local: el `MediaItem` lleva `addedBy` en sus extras para que la cola del reproductor lo pinte.

### 4.5 Cola completa

- Servidor: `JAM_MAX_UPCOMING_QUEUE` por defecto 500 en nuestra imagen; se anuncia en `/api/info`
  como `limits.maxUpcomingQueue`.
- Clientes: dejan de usar la constante 25 (`PartySync.MAX_PARTY_UPCOMING_QUEUE`,
  `DesktopPartySync.MAX_PARTY_UPCOMING_QUEUE`, el literal 26 de `PlayerConnection`) y leen el límite
  del servidor; 25 si el servidor no lo dice (original).
- Se sincroniza también el historial reciente (lo ya sonado) para que "anterior" funcione igual en
  todos: el servidor ya guarda la cola entera con `QueueIndex`; solo hay que dejar de recortar.
- `queueDelta` (ya existe) evita reenviar la cola entera en cada cambio.

### 4.6 Crear party y puente de Spotify

- `createParty()` sin parámetros de UI: nombre = el configurado (o el de la cuenta).
- Se eliminan los campos de nombre de `CreatePartySheet` y `JoinConfirmSheet` (Android y PC).
- `SpotifyJamBridge`: separar "crear party" de "abrir Spotify Jam" para poder llamar
  `startBridge()`/`stopBridge()` con la party ya creada. **A comprobar**: si la API de Spotify Jam
  permite abrir la sesión después; si no, el interruptor la crea en ese momento y re-genera el
  enlace de invitación de Spotify.

### 4.7 Código compartido (paridad Android/PC)

Hoy la lógica de cliente está duplicada (~2.600 líneas entre `ListenTogether.kt` y
`DesktopListenTogether.kt`) y también hay dos copias de la UI del reproductor (`sharedUi` y
`desktopUi`). Para no duplicar el rediseño:

- Interfaz `PartyClient` en `:shared` (estado, acciones) implementada por los dos clientes actuales.
- Nuevo `AccountDevices` en `:shared` (canal de cuenta + traspaso), escrito una vez.
- Modal Conectar, hoja Party y avatar de cola como composables en `sharedUi` que consumen esas
  interfaces; `desktopUi` los reutiliza en lugar de copiar.

---

## 5. Límites reales (no se pueden evitar del todo)

- **Un dispositivo dormido no aparece.** Sin notificaciones push (no hay FCM en el proyecto), un
  Android con la app cerrada no tiene socket. Aparecerá como "Visto hace X" y no se puede despertar
  de forma remota. Spotify lo resuelve con push; aquí sería otra fase (FCM o similar).
- **El secreto de cuenta es una playlist privada** "BitChord devices · …" en YouTube Music. Es lo
  que permite reconocer la misma cuenta sin servidor de cuentas. Se ve en la biblioteca.
- **Volumen remoto**: en Android se controla el volumen de la app (no el del sistema) salvo que se
  dé permiso extra.
- **Servidor en memoria**: si se reinicia, los dispositivos se reconectan solos; nadie pierde lo que
  suena, solo el emparejamiento momentáneo.

---

## 6. Fases

| Fase | Contenido | Verificación |
|---|---|---|
| **F0 Servidor** | Canal de cuenta (4.1), persona en party (4.3), `addedBy` (4.4), límite de cola en `/api/info` (4.5). Retirar `/api/devices/join` tras migrar. | `go test` con casos de traspaso, persona y sellado |
| **F1 Núcleo compartido** | `PartyClient`, `AccountDevices`, `DeviceHandoff`, nombre de dispositivo. | tests JVM en `:shared` |
| **F2 Conectar** | Modal (3.2), entrada del reproductor (3.1), modo control y mini barra. Android + PC. | dos dispositivos reales |
| **F3 Party** | Hoja Party (3.3), agrupación por persona, avatares en cola (3.4), cola completa, crear sin nombre, Spotify dinámico. | party con 3 cuentas, una con 2 dispositivos |
| **F4 Configuración** | Pantalla reducida (3.5); borrar `MyDevicesRoom`, landing y hojas viejas, y textos sobrantes. | compilación + revisión visual |
| **F5 Pulido** | Animaciones de traspaso, estados vacíos y de error, accesibilidad. | en dispositivo |

Cada fase deja la app usable: F0 no rompe a los clientes actuales; F2 puede salir sin F3.
