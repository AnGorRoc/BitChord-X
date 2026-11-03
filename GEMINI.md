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

---

## 5. Current State
- **Repositorio Base**: `https://github.com/AnGorRoc/BitChord-X.git`
- **Branch Activa**: `fvkinGordo` (rama de desarrollo personal de Pedro / fvkinGordo)
- **Estado Actual**:
  - Rama publicada y sincronizada con `origin/fvkinGordo`.
  - Commits integrados y testeados en build devDebug sobre dispositivo físico:
    - `afde70c1` feat(player): show analyzing mix badge below timeline scrubber
    - `2a606526` feat(automix): calibrate equal-power gain curves and tighter bass swap
    - `02d8ca6b` feat(ui): auto-focus search bar and lift bottom pill above keyboard
    - `4f42c9bf` docs: add fvkinGordo to maintainers table
    - `9bf4df37` feat(player): add playing from origin with clean floating action icons
    - `31181731` perf(ui): 120fps liquid glass & syllable glow optimizations
    - `8c1359d0` fix(glass): reduce surface opacity and add native liquid glass support to MiniPlayer
    - `750c4202` feat(player): add immersive player mode with cover reflection and veil
