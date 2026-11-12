# Feature `replay`

Responsable: **Mistic** · Rama: `feature/replay` · Paquete: `com.wwrp2.feature.replay`

> **Plan de desarrollo completo (fases, decisiones pendientes y riesgos): [`PLAN.md`](PLAN.md).**

## Qué es

Grabar las partidas en el **servidor** como replay, para ver highlights después. No se graba
vídeo: se guarda el flujo de paquetes (posiciones, chunks, entidades, bloques, sonidos, chat) en
un archivo `.mcpr` que se abre con **Replay Mod**. Desde ahí se puede ver la partida con cualquier
cámara: POV de cualquier jugador, cámara libre, travellings y exportación a vídeo.

Objetivo final: replays al estilo Hypixel, que se ven **dentro del propio servidor**, sin mods.
El mismo `.mcpr` sirve también para ReForgedPlay (vídeos cinematográficos).

## Decisiones tomadas

| Tema | Decisión | Por qué |
|---|---|---|
| Qué se graba | Datos (paquetes), no vídeo | Coste casi nulo en directo y cualquier ángulo al revisar |
| Dónde se graba | Servidor, un único grabador | Sin instalar nada en los clientes para grabar |
| Visor | Replay Mod (en Forge 1.20.1: **ReForgedPlay**, port de la comunidad; el original es Fabric) | Ya trae línea de tiempo, keyframes y exportación. Visor propio solo si resulta inviable en 1.20.1 Forge |
| Formato | `.mcpr` | Es lo que lee Replay Mod |
| Escala | Menos de 20 jugadores | Estimación del equipo (~98 % seguro) |
| Mapa | ≈1000 × 1000 bloques, solo Overworld | Dato del equipo |
| Qué se graba | Una zona del mapa (dos esquinas), un archivo por ronda | Cámara libre por todo el mapa y todos los marcadores juntos |
| Cuándo se graba | Automático con `rondas` + comandos manuales | Que no dependa de que un admin se acuerde |
| Cómo se graba la zona | Grabador enganchado con Mixin (aprobado y configurado el 2026-11-12) | Un jugador falso no cubre 1000×1000 ni ve proyectiles lejanos (ver `PLAN.md`) |
| Marcadores | Automáticos, y el admin puede añadir, editar y borrar | Petición del equipo |
| Dependencias | Ninguna nueva | Replay Mod solo hace falta para reproducir, no para grabar |

### Visor: ReForgedPlay (decidido)

Se ve el replay con **ReForgedPlay** dentro del propio modpack Forge, para que también se vea el
contenido modded. Solo lo necesita quien revise o edite replays; grabar lo hace el servidor.
Descartados: Replay Mod original en una instancia Fabric (no ve el contenido modded) y Sinytra
Connector + Replay Mod original (beta, sin confirmación de que funcione). Plan B si ReForgedPlay
falla con Forge 47: Replay Mod original en Fabric para contenido vanilla.

## Hallazgos de la investigación (Forge 47 / 1.20.1)

- **Grabar a un jugador (fase 0) no necesita Mixin.** El pipeline de Netty de cada jugador es
  `splitter, decoder, prepender, encoder, unbundler, bundler, packet_handler`. Un handler de
  salida colocado con `pipeline.addBefore("encoder", ...)` recibe cada paquete **ya codificado**
  (id + datos), que es justo lo que guarda el `.mcpr`. Los bundles ya vienen separados.
- **Formato `.mcpr`** (zip): `recording.tmcpr` (por paquete: int tiempo en ms, int longitud,
  bytes), `metaData.json` (singleplayer, serverName, duration, date, mcversion, fileFormat,
  fileFormatVersion, protocol, generator, selfId, players) y `markers.json`: array de
  `{realTimestamp, value:{name, position:{x,y,z,yaw,pitch,roll}}}`.
- **Problema real: el estado inicial.** Al empezar a grabar con los jugadores ya conectados hay
  que fabricar a mano los paquetes de «acabo de entrar» (login, jugadores, posición, chunks,
  entidades, inventario...). Es la parte más trabajosa.
- **Referencia:** ServerReplay (senseiwells, MIT, Fabric) hace lo mismo; sirve de guía de diseño,
  no se puede usar tal cual porque es Fabric.

## Plan por fases

Ver [`PLAN.md`](PLAN.md): fase 0 grabador base, 1 grabador de zona, 2 rondas, 3 marcadores,
4 visor en el servidor, 5 escala.

## Comandos (permiso 2)

| Comando | Qué hace |
|---|---|
| `/replay zona` | Muestra la zona guardada |
| `/replay zona <x1 z1> <x2 z2>` | Define la zona (dos esquinas, máximo 64 chunks por lado) |
| `/replay start` / `/replay stop` | Graba la zona / para y guarda en `replays/zona_<fecha>.mcpr` |
| `/replay estado` | Qué se está grabando: tiempo, % de terreno, entidades, tamaño |
| `/replay jugador start\|stop [jugador]` | Graba lo que ve un jugador concreto (`replays/jugadores/`) |

## Estado

- [x] **Paso 1: grabador básico (probado en servidor dedicado el 2026-11-11).**
  - `ReplayRecorder`: handler de Netty tras el `encoder`, escritura en hilo aparte y empaquetado a
    `.mcpr` con `recording.tmcpr`, `metaData.json` y `markers.json`.
  - `ReplayManager` y comandos `/replay start|stop [jugador]` y `/replay list` (permiso 2). La
    grabación se para sola si el jugador se desconecta o el servidor se apaga.
  - Los archivos salen en `<carpeta del servidor>/replays/<jugador>_<fecha>.mcpr`.
  - Verificado leyendo el archivo entero: 17.166 paquetes en 43,5 s, todos legibles, tiempos
    crecientes, primer paquete Login Success (`0x02`), metadatos con versión 1.20.1 y protocolo 763.
  - **Limitación:** falta el estado inicial. El análisis da 0 paquetes Join Game, 0 de posición
    inicial y solo los chunks cargados después de empezar. Sin eso, ReForgedPlay no podrá abrirlo.
- [x] **Mixin configurado (2026-11-12):** plugin MixinGradle 0.7.38, procesador Mixin 0.8.5 (la que
  trae Forge 47) y config propia `wwrp2.replay.mixins.json` con los Mixin en
  `com.wwrp2.feature.replay.mixin`. Si un punto de enganche no existe, el servidor no arranca y lo
  dice (`required` + `defaultRequire: 1`). Otras features que quieran Mixin añaden su propia config
  y una línea `config` en `build.gradle`.
- [x] **Fase 1: grabador de zona (2026-11-12), probado en servidor sin jugadores.** Graba todo lo
  que pasa en un rectángulo del Overworld con Mixin, sin jugador falso, y genera el estado inicial
  (login, mundo, hora, clima, jugadores y todo el terreno de la zona). Detalle, resultados y
  limitaciones en [`PLAN.md`](PLAN.md). Probado con jugador y **visto en Replay Mod (Fabric
  1.20.1): funciona.**
- [x] **Scoreboard y vida (2026-11-13):** el replay incluye el estado inicial del scoreboard real
  (equipos y objetivos visibles; los cambios ya se grababan) y la vida de cada jugador bajo su
  nombre con un objetivo que solo existe en el replay. Detalle en [`PLAN.md`](PLAN.md). Falta
  verlo con jugadores.
- [ ] Fase 2: rondas. Fase 3: marcadores. Fase 4: visor en el servidor. Fase 5: escala.

## Cómo probar

- **Solo en servidor dedicado** (`./gradlew runServer`), como en el evento. El anfitrión de un mundo
  singleplayer/LAN no se puede grabar (su conexión es local y no codifica paquetes).
- Primera vez: `eula=true` en `run/eula.txt` y `online-mode=false` en `run/server.properties` (el
  cliente de desarrollo entra como `Dev`, sin cuenta).
- La consola de `runServer` lanzada por Gradle **no acepta comandos**. Para dar op, añade el
  jugador a `run/ops.json` con el servidor parado (UUID offline de `Dev`:
  `380df991-f603-344c-a090-369bad2a924a`), o lanza el servidor desde IntelliJ
  (`./gradlew genIntellijRuns`), donde la consola sí funciona.
- Cliente: `./gradlew runClient` → Multijugador → `localhost`.
- Para lanzar comandos sin cliente se puede activar RCON en `run/server.properties`
  (`enable-rcon=true`, `rcon.password=...`) y usar cualquier cliente RCON. Acordarse de quitarlo.

## Cómo ver un replay

Hasta la fase 4 (visor en el servidor) solo se puede ver con un visor externo.

- **Probado y funciona (mundo vanilla):** instancia aparte con Minecraft 1.20.1 + Fabric + Fabric
  API + Replay Mod (de replaymod.com). Copiar el `.mcpr` a `replay_recordings/` de esa instancia y
  abrirlo con **Replay Viewer**. No ve el contenido de otros mods.
- **Para el modpack completo (sin probar todavía):** ReForgedPlay 0.3.2 (ferriarnus, Forge 1.20.1),
  por CurseForge; el repositorio (`github.com/ferriarnus/ReForgedPlay`, rama `1.20.1`) no publica
  descargas. Instancia normal (launcher de CurseForge o Prism) con 1.20.1 + Forge 47.x +
  ReForgedPlay; mismo procedimiento con `replay_recordings/` y **Replay Viewer**.
- **No en `runClient`:** el entorno de desarrollo usa otros nombres de código y los mods publicados
  suelen romperse ahí. Nuestro mod no hace falta para ver.

## Acciones futuras

- [x] ~~Investigar cómo acceder al pipeline de red por jugador en Forge 47.~~ Hecho (ver hallazgos).
- [x] ~~Diseñar el esqueleto del grabador.~~ Hecho en el paso 1.
- [x] ~~Paso 2: estado inicial.~~ Hecho dentro de la fase 1 (grabador de zona).
- [ ] Probar el `.mcpr` en ReForgedPlay y comprobar que muestra los marcadores de `markers.json`.
- [ ] Decidir qué eventos de partida cuentan como highlight y su prioridad.
- [ ] Definir la sintaxis de los comandos de admin para marcadores y sus permisos.
- [ ] Fase 2: arrancar y parar solo con la feature `rondas` (decidido: Preparación → fin de ronda).
- [ ] Probar con ~20 jugadores: tamaño de archivo, carga del servidor y disco.
- [ ] Política de almacenamiento: dónde se guardan los replays y cuándo se borran los viejos.
- [ ] Si ReForgedPlay no sirve: plan B Replay Mod original en Fabric (solo contenido vanilla).

## Errores antiguos corregidos

Cada fallo encontrado y corregido, con qué pasaba y cómo se arregló.

| Fecha | Qué fallaba | Cómo se corrigió |
|---|---|---|
| 2026-11-11 | `/replay start` daba «unexpected error» al probar en un mundo abierto a LAN. El anfitrión de singleplayer/LAN usa una conexión local en memoria, sin `encoder` en el pipeline, y `addBefore("encoder", …)` lanzaba excepción. | `start()` comprueba que existe `encoder` y, si no, falla con un mensaje claro. Para probar hay que usar servidor dedicado (`runServer`), como en el evento. |
| 2026-11-11 | Si `start()` fallaba, el grabador quedaba registrado y al cerrar se guardaba un `.mcpr` vacío. | El grabador solo se registra si arranca bien; si no, `discard()` cierra y borra el temporal. |
