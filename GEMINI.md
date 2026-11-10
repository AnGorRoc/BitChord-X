# GEMINI.md - BitChordX Development Context & Documentation

## 1. Project Overview
- **Project**: BitChordX (fork y evolución de BitChord, basado en kushagrasinghx/BitChord y mantenido por AnGorRoc y fvkinGordo / Pedro).
- **Official Repository**: [https://github.com/AnGorRoc/BitChord-X](https://github.com/AnGorRoc/BitChord-X)
- **Local Workspace**: `C:\Users\pedro\Documents\BitChord`
- **Target OS / Platform**: Android (Jetpack Compose, Kotlin Multiplatform, Media3 ExoPlayer).

---

## 2. Environment & Tooling
- **OS**: Windows (PowerShell)
- **Android SDK**: `C:\Users\pedro\AppData\Local\Android\Sdk`
- **Java / Gradle**: Gradle Wrapper (`.\gradlew.bat`), AGP 8.10.x, Kotlin 2.x
- **Build Targets**:
  - Dev Debug Kotlin: `.\gradlew.bat :app:compileDevDebugKotlin`
  - Dev Debug Build: `.\gradlew.bat :app:assembleDevDebug`

---

## 3. Git Workflow & PR Directives (POR ANGOR Y PEDRO - OBLIGATORIO)
1. **Regla de oro previa a cualquier commit**:
   - **NUNCA commitear sin la orden explícita de Pedro** (esperar confirmación: *"commitea"* o *"hace el commit"*). Jamás hacer un commit de forma proactiva o automática.
   - **NUNCA** realizar un commit o push a ciegas.
   - Antes de commitear, ejecutar **SIEMPRE** `git fetch` y comparar el árbol local contra `origin/main` (`git diff origin/main` o revisión de PR).
2. **Inspección de contenido**:
   - Verificar rigurosamente que ningún cambio rompa código existente, que no haya colisiones de merge ni regresiones antes de consolidar.
3. **Entorno de trabajo**:
   - Trabajar siempre sobre el repositorio local y el remote oficial de GitHub (`AnGorRoc/BitChord-X`).

---

## 4. History & Key Implementations
Previamente se desarrollaron e integraron las siguientes funcionalidades clave para la versión personalizada del proyecto:

1. **Dev Branding & Adaptive Icon (`feat(branding)`)**:
   - Icono adaptativo personalizado para builds de desarrollo (`devDebug`).
   - Recursos mipmap generados para todas las densidades (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) con fondo blanco y diseño limpio de tres franjas de color.

2. **Morphing Search Bottom Bar & Persistent MiniPlayer (`feat(ui)`)**:
   - Barra de búsqueda integrada en la barra de navegación inferior (`MorphingBottomBar`).
   - Transición animada entre los tabs estándar y el campo de búsqueda expansible.
   - Mini reproductor persistente visible sobre la barra mientras se busca o navega.
   - Gesto **Hold & Slide** sobre la barra de navegación inferior para cambiar de tabs deslizando el dedo sin soltar.
   - Indicador de selección tipo píldora translúcida con soporte para desenfoque y liquid glass.

3. **Reorganización de Ajustes & Letra sobre Scrubber (`feat(settings)`)**:
   - Categorización de pantallas de configuración.
   - Opción para visualizar la línea de letra sincronizada inmediatamente sobre la barra de progreso (scrubber) del reproductor (`NowPlayingScreen`).
   - Integración con las características de Hi-Res Lossless / Monochrome source de AnGor.

4. **Automix DSP Optimization & Analyzing Indicator (`feat(automix)`, `feat(player)`)**:
   - Curvas de ganancia equal-power ($L^2 + R^2 \approx 1.0$) y swap de bajos ajustado en el downbeat para transiciones más contundentes y limpias en LoopMixSession.
   - Cartel reactivo *"Analizando mezcla"* bajo el scrubber con icono `AutoAwesome`, animación de respiración y shimmer mientras se calculan los compases antes de mostrar la marca en la línea de tiempo.

5. **Harmonic Key Wheel, BPM Stretch Guard & Lyric Preservation (`feat(automix)`)**:
   - Sistema de compatibilidad tonal Camelot Wheel (`CamelotKey.kt`): soporte de 24 tonalidades, armónicos relativos (ej. 8A <-> 8B), quintas adyacentes y protección contra choque armónico (distancia $\ge 2$).
   - BPM Stretch Guard: tope máximo de alteración de tempo ($\le 16\%$) para evitar estiramientos no naturales.
   - Preservación de letra y protección vocal (`lastVocalTime`, `firstVocalTime`): asegura que el tema saliente cante hasta el último verso y que el tema entrante comience a mezclarse únicamente en la intro instrumental, evitando que las voces se superpongan o corten.

6. **Dock Compaction on Scroll & Inline MiniPlayer (`feat(ui)`)**:
   - Al scrollear hacia abajo en Home, Explore y Library, el dock inferior y el MiniPlayer se compactan fluidamente en una sola fila minimalista de estilo *GlassNavBar*:
     - **Izquierda:** Círculo (58dp) con el icono de la pestaña activa.
     - **Centro:** MiniPlayer inline con portada, título, artista con elipsis, play/pause (con soporte de bloqueo para salas de fiesta) y gestos táctiles (skip swipe / drag up para abrir el reproductor).
     - **Derecha:** Círculo (58dp) del botón de búsqueda.
   - El MiniPlayer flotante superior desaparece de forma animada mediante `shrinkVertically + fadeOut`.
   - Al scrollear hacia arriba o pulsar sobre el círculo de pestaña activa, el dock vuelve a desplegarse fluidamente en dos niveles.
   - El botón de Party (Listen Together) viene activado por defecto en `AppSettings`.

7. **Native Morph Play/Pause, Kinetic Skips & Spring MiniPlayer (`feat(player)`, `feat(ui)`)**:
   - Transición de play/pause con morph suave mediante `AnimatedContent`, escala elástica y `fadeIn/fadeOut` en `PlayerControls`, `MiniPlayer` y `GlassNavBar`.
   - Impulso cinético lateral (*kinetic kick*) con retorno por `spring` al presionar botones de salto previo y siguiente.
   - Animación *spring pop* con escala y desplazamiento vertical al desplegar y ocultar el MiniPlayer flotante superior.
   - Integración del icono de logo BitChord en `FrostedTopBar` para pestaña Home eliminando texto duplicado.
   - Gradiente armónico y afinación cromática HSL para artistas en `DetailScreen` y modo inmersivo en `NowPlayingScreen`.

---

## 5. Current State
- **Repositorio Base**: `https://github.com/AnGorRoc/BitChord-X.git`
- **Branch Activa**: `main` (commits de `fvkinGordo` consolidados y verificados)
- **Estado Actual**:
  - Commits de `fvkinGordo` integrados en `main`:
    - `ca11746f` feat(spotify): enhance playlist import with intro dialog, destination selector and smooth animations
    - `679cd6f0` feat(sing): redesign sing sheet with liquid capsule fader and dsp engine selector
    - `18ea0e83` feat(radio): support dynamic radio detail pages and playback source origin
    - `55c4359d` feat(party): add interactive qr code zoom modal and tune keyboard compaction
    - `3360f30c` feat(ui): enhance compact dock and search transitions with spring animations
    - `cea79dd8` fix(spotify): show pull-to-refresh spinner only on manual reload
    - `2a2c5f1a` feat(ui): add spring pop and scale transitions for floating miniplayer
    - `546c8c8d` feat(player): add morph play/pause transition and kinetic skip kick to native icons
    - `79e4b8f7` feat(ui): compact dock on scroll down with inline miniplayer and active tab circle
    - `a3b4b144` feat(settings): enable listen together button by default in player capsule
    - `083449b0` feat(player): restore clean v1 3-button action row (lyrics, cast, queue) and deprecate experimental morphing dock
    - `eaf45011` docs: update development context with recent commits
    - `d84b7ad6` feat(ui): add liquid glass morphing dock to full player with context actions
    - `e85bf81c` feat(player): unify static artwork size with animated covers and smooth dissolve
    - `38e4a772` style(reflection): tune mesh cover reflection strength and fade bounds
    - `fc1ede0d` feat(automix): preserve lyrics and prevent vocal overlap with camelot key guard
    - `afde70c1` feat(player): show analyzing mix badge below timeline scrubber
    - `2a606526` feat(automix): calibrate equal-power gain curves and tighter bass swap
    - `02d8ca6b` feat(ui): auto-focus search bar and lift bottom pill above keyboard
    - `4f42c9bf` docs: add fvkinGordo to maintainers table
    - `9bf4df37` feat(player): add playing from origin with clean floating action icons
    - `31181731` perf(ui): 120fps liquid glass & syllable glow optimizations
    - `8c1359d0` fix(glass): reduce surface opacity and add native liquid glass support to MiniPlayer
    - `750c4202` feat(player): add immersive player mode with cover reflection and veil
