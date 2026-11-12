# Puente de Spotify Jam: plan

Estado: **plan + reglas probadas**. Las reglas de decisión están en
`shared/.../listentogether/bridge/JamBridgeRules.kt` con sus pruebas. Lo que toca la red de Spotify
**no está probado** y depende de una prueba con una cuenta real (fase S0).

Principio del dueño de la app: **manda el dispositivo que está sonando.**

---

## 1. Cómo es hoy (lo que hay que arreglar)

Leído de `SpotifyJamBridge.kt`, `SpotifyJamApi.kt` y `SpotifyDealerClient.kt`:

| Hallazgo | Consecuencia |
|---|---|
| Solo sincroniza **reproducción** (pista, play, pausa, seek). **No sincroniza la cola.** El Dealer ya recibe `next_tracks` pero el puente lo ignora. | Hay que construir toda la parte de cola. |
| `onLocalSeek` manda `seek_to` **sin posición**. | El seek hacia Spotify no funciona. |
| `onLocalTrackChanged` añade la canción al final de la cola de Spotify **sin saltar a ella**. | Spotify no cambia de canción. |
| Una canción de Spotify sin equivalente se publica en la party con id `spotify_<id>`. | Los demás miembros de BitChord intentan reproducir un id que no existe. |
| El guardia de ecos compara pista y posición con ±2,5 s. | Frágil: un seek real a menos de 2,5 s se pierde. |
| El puente solo existe en Android. El PC no tiene nada. | Paridad pendiente (ver §8). |
| El interruptor de Spotify está dentro de "crear party". | Hay que moverlo a la pantalla de configuración (§5). |

---

## 2. Modelo

### 2.1 Autoridad
Un solo valor, `Authority = BITCHORD | SPOTIFY`: de dónde sale el sonido del anfitrión de la jam.

- El que está sonando solo es la fuente.
- Si nadie suena, se queda el último.
- Si suenan los dos (transitorio), gana el que ya sonaba antes; el que arrancó último cede.
- Cambiar de fuente **no cambia lo que suena ni la cola**: la nueva fuente continúa desde la canción
  y posición actuales.

Cómo se detecta "suena en Spotify" depende de S0 (ver §4): lo esperable es mirar en el clúster del
Dealer qué dispositivo está activo y si `is_playing`.

### 2.2 Entrada de cola
Cada canción de la cola compartida es una `BridgeEntry` con lo que sabe cada catálogo:

```
BridgeEntry(key, videoId?, spotifyUri?, título, artista, portada, duración)
  videoId == null   → BitChord no la tiene  (solo Spotify)
  spotifyUri == null → Spotify no la tiene  (solo BitChord)
```
La resolución se hace una vez y se cachea, **también el "no encontrada"** (para no volver a buscar
cada vez). Spotify→BitChord ya existe (`SpotifyImporter.lookUp`). BitChord→Spotify hay que
escribirlo: búsqueda en el catálogo de Spotify con el mismo `TrackMatcher` (título, artista, duración).

### 2.3 Qué pasa en cada caso (todos con prueba automática)

| Quién suena | Quién añade | ¿Está en el otro catálogo? | Resultado |
|---|---|---|---|
| Spotify | BitChord | No | **Se rechaza**, aviso al usuario, nada cambia |
| Spotify | BitChord | Sí | Se añade y se pasa a la cola de la jam |
| Spotify | un miembro de la jam | No está en BitChord | Entra en la cola de BitChord con los datos que dé la API; suena normal en Spotify |
| BitChord | un miembro de la jam | No está en BitChord | Se queda en la cola, **oscurecida**; al llegarle el turno se salta, en la cola y en la jam |
| BitChord | BitChord | No está en Spotify | La jam no se entera; al sonar esa canción la jam queda **en pausa** hasta que acabe |
| BitChord | BitChord | Sí | Se añade y se pasa a la cola de la jam |

### 2.4 Simplificaciones propuestas (para que sea fiable)

1. **La cola de Spotify no se puede editar desde fuera**: solo se puede añadir y saltar. Por eso:
   - la cola de la jam es una **vista corta** (las próximas **10** canciones que Spotify tiene),
     no un espejo completo;
   - de la jam solo se **exige** la canción actual; si la cola de Spotify se desvía, se tolera;
   - **mientras suena en Spotify, reordenar o quitar canciones en BitChord se bloquea** con un aviso
     ("Spotify controla la cola ahora"). Añadir sí se puede. Cuando BitChord vuelve a ser la fuente,
     se desbloquea.
2. **Para pasar la jam a una canción concreta se usa "reproducir este URI" y no "añadir y saltar"**,
   si S0 confirma que la jam lo acepta (si no, añadir + saltar hasta llegar).
3. **Una sola jam por party** y solo el dueño de la jam habla con Spotify. Los demás miembros de
   BitChord no necesitan Spotify; los invitados de Spotify cambian cosas a través del dueño.

---

## 3. Casos límite y qué hacer

| Caso | Decisión |
|---|---|
| El dueño cierra la app | Se cierra la jam (`DELETE session`) en `onDestroy`/al terminar el servicio, mejor esfuerzo. Si no se logra, Spotify la caduca. |
| El dueño sale de la party | Se cierra la jam. La party sigue. |
| La party termina | Se cierra la jam. |
| Se pulsa "Detener" | Se cierra la jam y el enlace/QR deja de valer. Los invitados de Spotify se desconectan. |
| Se vuelve a "Iniciar" | Jam nueva, **enlace y QR nuevos**. |
| Cuenta de Spotify gratis | El interruptor no se ofrece; se dice por qué. |
| Sin sesión de Spotify | Se ofrece iniciar sesión; no se crea nada. |
| Falla la creación (HTTP, token, etc.) | Aviso "No fue posible crear la jam". Sin reintentos silenciosos. La party no se ve afectada. |
| Se cae la conexión del Dealer | Reintentos con espera creciente; si pasan 60 s, aviso y la jam queda "desconectada" (no se cierra). |
| Spotify responde 401 (token caducado) | Renovar token una vez; si falla, aviso y detener el puente. |
| La misma canción dos veces en la cola | Se identifican por `key`, no por URI; la cola puede repetir. |
| Canción con varias versiones | Gana la mejor coincidencia por duración (±3 s) y artista; si la duda es grande, se trata como "sin equivalente". |
| Ecos (un cambio que enviamos vuelve como evento) | Guardia por **identificador de acción**, no por posición ±2,5 s. |
| Cambio de fuente mientras suena | Sin saltos: la nueva fuente continúa en la misma posición. Si la canción actual no existe en la nueva, se avanza a la siguiente reproducible. |
| La jam queda con una canción "solo BitChord" sonando | Jam en pausa; al terminar, se mueve a la siguiente que tenga URI. |
| Posición/seek | Se compensa la latencia con la marca de tiempo de Spotify (ya existe). Umbral de seek 1,5 s (ya existe). |
| Muchos cambios seguidos | Los comandos a Spotify se serializan y se agrupan (un `Mutex` + coalescencia, máximo 2 por segundo). |

---

## 4. Fases y pruebas

| Fase | Contenido | Cómo se prueba | Estado |
|---|---|---|---|
| **S0 Sonda** | Con una cuenta Premium real: crear jam, ver los mensajes del Dealer, probar `add_to_queue`, `skip_next`, `resume/pause`, `seek_to` con posición, "reproducir URI", y comprobar **si hace falta un dispositivo real de Spotify activo** o si basta con el dispositivo web del puente. | A mano, con registro (ver §6). **Necesita a una persona con su cuenta.** | ⛔ No hecha |
| **S1 Reglas** | `JamBridgeRules` (§2.3, §2.4). | `JamBridgeRulesTest`, automática | ✅ ver resultado abajo |
| **S2 Resolutor** | BitChord→Spotify (búsqueda + caché + negativos) y Spotify→BitChord con la misma caché. | Pruebas JVM con un catálogo falso | Pendiente |
| **S3 Planificador** | Estado de autoridad + cola de comandos serializada, con un `FakeSpotify` y un `FakeParty` que registran lo que reciben. Cubre todos los casos de §3. | Pruebas JVM, sin red | Pendiente |
| **S4 Conexión** | Sustituir los puntos de entrada del puente actual; arreglar el seek y el eco; guardar/leer `next_tracks`. | Pruebas JVM del lector del Dealer con mensajes grabados en S0 + manual | Pendiente (necesita S0) |
| **S5 Interfaz** | Interruptor Iniciar/Detener y botón "Ver QR" en la configuración; avisos; canción oscurecida en la cola. | Compilación + revisión en dispositivo | Pendiente |
| **S6 Paridad PC** | Solo si S0 lo permite (ver §8). | — | Pendiente |

### Lo que S0 tiene que contestar (las dudas reales)
1. ¿La jam creada con el "dispositivo web" del puente suena en los invitados aunque el anfitrión no
   tenga la app de Spotify abierta? ¿O el anfitrión necesita un dispositivo de Spotify activo?
2. Si el anfitrión tiene Spotify instalado: ¿al arrancar BitChord suenan **los dos** en su teléfono?
3. ¿Existe un comando para reproducir un URI concreto en la jam?
4. ¿`next_tracks` del Dealer trae la cola completa o solo las próximas?
5. ¿Cómo aparece en el Dealer que un invitado añadió o quitó una canción? (lo necesito para saber
   **quién** la añadió y para detectar quitadas)
6. ¿Se puede cerrar la jam desde `onDestroy` a tiempo (la petición tiene que salir antes de que
   muera el proceso)?

---

## 5. Interfaz (según tu descripción)

En la pantalla de **configuración** de Listen Together, sección "Spotify Jam":

- Fila de estado: cuenta de Spotify y nivel. Si es gratis: "Hace falta Spotify Premium para crear una
  jam".
- Botón **Iniciar jam** ⇄ **Detener jam** (el mismo botón cambia). Mientras crea, spinner.
- Con la jam activa aparece **Ver QR** (abre la hoja de QR/enlace con el botón de compartir) y el
  nombre de los invitados de Spotify.
- Solo se ofrece si hay una party activa y eres su anfitrión (o el dueño de la jam).
- Errores: "No fue posible crear la jam." (una línea, sin detalles técnicos; el detalle va al log).

En el reproductor / cola:
- Canciones "solo Spotify" mientras suena BitChord: **atenuadas**, con título y artista, sin portada
  animada, y un icono pequeño de "no disponible aquí".
- Aviso de rechazo (canción que Spotify no tiene): snackbar "Spotify no tiene esta canción. No se
  añadió."
- Cola bloqueada: snackbar "Spotify controla la cola ahora."
- En la cola, quien añadió cada canción aparece con su foto (ver `listen-together-v2.md` §3.4); las
  añadidas desde Spotify muestran el logo de Spotify en lugar de una foto si no hay equivalente.

---

## 6. Prueba S0 (lo que necesito de ti)

Para no adivinar la API, añadiré un modo de diagnóstico **solo en la build dev**: un botón oculto
"Sonda de Spotify Jam" que ejecuta una lista de pasos y deja un archivo de registro
`spotify-jam-probe.txt` con las respuestas de cada llamada (sin tokens). Tú solo tendrías que:

1. Tener la sesión de Spotify iniciada en la app (cuenta Premium).
2. Pulsar el botón y esperar.
3. Pasarme el archivo.

Con eso resuelvo las seis dudas de §4 y cierro S4. Sin esa prueba, S4 y S5 serían suposiciones.

---

## 7. Qué NO se promete

- Que Spotify no cambie la API interna: es un servicio no documentado; si cambia, el puente falla y se
  avisa ("No fue posible…").
- Sincronía perfecta de la cola: Spotify no deja quitar ni reordenar desde fuera, por eso el diseño la
  trata como una vista.
- Que los invitados de Spotify oigan las canciones que Spotify no tiene.

## 8. Paridad con PC

El puente hoy solo existe en Android y usa `SpotifyToken`, que obtiene el token de la sesión web de
Spotify dentro de la app. En el PC habría que repetir ese inicio de sesión. Propuesta: **no** incluir
el PC en la primera entrega del puente; en el PC la party funciona igual y la jam se crea desde el
teléfono. Si quieres paridad completa, S6 pasa a ser obligatoria y habría que mover `SpotifyJamApi` a
`:shared`.
