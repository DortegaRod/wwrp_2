# Plan de desarrollo: feature `replay`

Responsable: **Mistic** · Contexto y estado en [`README.md`](README.md)

Objetivo: un sistema de replays al estilo Hypixel. Se graban las rondas en el servidor y se ven
**dentro del propio servidor**, sin mods, con controles tipo vídeo y marcadores de highlights. El
mismo archivo `.mcpr` sirve además para ReForgedPlay, si alguien quiere montar vídeos cinematográficos.

## Datos de partida

| Dato | Valor |
|---|---|
| Jugadores | Menos de 20 (≈98 % seguro) |
| Mapa | ≈1000 × 1000 bloques (≈63 × 63 chunks), solo Overworld |
| Qué se graba | Una zona del mapa definida con dos esquinas; un archivo por ronda |
| Cuándo | Automático con la feature `rondas` + comandos manuales |
| Visor | En el servidor (estilo Hypixel); ReForgedPlay como extra |

## Qué tomamos del sistema de Hypixel

Fuente: [Dev Blog #10: Replay System Technical Rundown](https://hypixel.net/threads/dev-blog-10-replay-system-technical-rundown.3234748/)

| Idea de Hypixel | ¿La usamos? | Por qué |
|---|---|---|
| Grabar eventos («Recordables») en vez de paquetes | **No** | Funciona porque Hypixel es vanilla. En un modpack, cada arma, vehículo o efecto de otro mod necesitaría su propio evento. Grabando paquetes, el contenido modded se graba solo. |
| Ver el replay dentro del servidor | **Sí** | Nadie tiene que instalar nada y se puede ver en grupo. |
| Al saltar en el tiempo, aplicar solo el último estado de cada entidad y bloque | **Sí, adaptado** | Saltar es rápido sin mandar todos los paquetes intermedios. |
| Buffer a disco, comprimir al terminar, número de versión del formato | **Sí** | Lo primero ya se hace; la versión del formato se añade. |
| Compresión a nivel de bits | **No, por ahora** | Optimización prematura (lo dicen ellos). El zip ya reduce ~5×. Primero medir. |

## Por qué hace falta un grabador de zona (y no un «cámara» invisible)

Comprobado en el código de Minecraft 1.20.1:

- La distancia de visión es **global** (`ChunkMap.viewDistance`), no por jugador. Un solo jugador
  falso que cubriera 1000×1000 obligaría a poner `view-distance=32` a todos los jugadores.
- Las entidades solo se envían si están **cerca** del que mira (≈64 bloques los proyectiles,
  ≈160 los mobs), por mucha distancia de visión que haya. Una bala a 300 bloques del cámara no se
  grabaría. Cubrir el mapa así exigiría decenas de cámaras.

Por eso el grabador se engancha con **Mixin** a los puntos donde el servidor reparte chunks,
cambios de bloques, entidades, sonidos y partículas, y «ve» toda la zona sin depender de
distancias. Es el enfoque de ServerReplay (MIT), que usamos como referencia.

## Fases

| Fase | Qué incluye | Estado |
|---|---|---|
| **0. Grabador base** | Handler de Netty tras el `encoder`, escritura en hilo aparte, `.mcpr`, comandos `/replay start\|stop\|list`. | ✅ Hecho (2026-11-11) |
| **1. Grabador de zona** | Ver detalle abajo. | ✅ Hecho (2026-11-12). Probado con jugador y **visto en Replay Mod (Fabric 1.20.1)**: funciona |
| **2. Rondas** | Arranca solo al pasar a Preparación y para al terminar la ronda (`RoundPhaseChangeEvent`). Archivos `ronda-N_fecha.mcpr`. Los comandos manuales se mantienen. | ✅ Hecho (2026-11-14). Probado sin jugadores |
| **3. Marcadores** | Automáticos: kills, muertes y cambios de fase. Comandos de admin para listar, añadir, editar y borrar en la grabación en curso. Multikills y rachas, más adelante. | ✅ Hecho (2026-11-16). Kills sin probar con jugadores |
| **4a. Visor básico** | `/replay ver <archivo>`. Aislar al espectador del mundo real, mandarle el replay con sus tiempos y ponerlo en vuelo libre. Al salir, volver a su sitio en el mundo real. Solo admins y fuera de ronda. | 🔧 Programado (2026-11-16), falta probarlo con cliente |
| **4b. Controles** | Items en la barra: pausa/play, velocidad (0,5×–4×), ±10 s. Saltar aplicando el último estado (idea de Hypixel). | |
| **4c. Highlights** | Menú de marcadores como capítulos para ir directo a cada kill. Seguir a un jugador en primera o tercera persona. | |
| **4d. Ver en grupo** | Varios espectadores en la misma sesión; un líder controla. | |
| **5. Escala** | Prueba con ~20 jugadores: tamaño, CPU y disco. Política de borrado de replays viejos. Más compresión solo si los números lo piden. | |
| **Extra** | Comprobar que el `.mcpr` abre en ReForgedPlay con el modpack completo (vídeos cinematográficos). Ya abre en Replay Mod original (Fabric). | Cuando se pueda |

### Detalle de la fase 1: grabador de zona

1. ~~Traer `develop` a la rama (incluye la feature `rondas`).~~ Hecho.
2. ~~Configurar Mixin.~~ Hecho: `settings.gradle`, `build.gradle` y `src/main/resources/wwrp2.replay.mixins.json`.
3. ~~`/replay zona <esquina1> <esquina2>`~~ Hecho: se guarda en `<mundo>/wwrp2_replay_zona.json`.
   Máximo 64 chunks por lado (el cliente del replay solo guarda los chunks a 32 + 3 del centro).
4. ~~Estado inicial~~ Hecho. Estado inicial al empezar: Login Success, Join Game, todos los chunks de la zona con luz,
   entidades presentes (con equipo, metadatos y pasajeros), hora, clima, borde del mundo, lista de
   jugadores.
5. ~~Mixin de grabación~~ Hecho (`replay.mixin`): `ChunkHolder` (bloques, luz y block entities,
   aunque nadie mire el chunk), `TrackedEntity` (todo lo de cada entidad), `PlayerList` (difusiones
   globales, por dimensión, sonidos/efectos por punto y mensajes del sistema) y `ServerLevel`
   (partículas, explosiones y bloques rompiéndose). Las entidades que entran o salen de la zona se
   revisan cada tick. El chat de jugadores se graba con `ServerChatEvent`. Sin MixinExtras (Forge 47
   no lo trae). Plan original: mixins que copian al grabador lo que el servidor reparte en la zona:
   chunks que se cargan o descargan, cambios de bloques, entidades (todas, sin límite de
   distancia), sonidos, partículas, explosiones, chat y lista de jugadores.
6. ~~Reutilizar `ReplayRecorder`~~ Hecho: ahora acepta paquetes como objetos además de bytes.
7. ~~Versión del formato propio~~ Hecho: `generator` = `wwrp-2 replay v1`.
8. ~~Validar con el analizador~~ Hecho sin jugadores: zona de 20×20 chunks, 14 s, 1.349 paquetes sin
   errores; Join Game, los 400 chunks con luz, 130 entidades con metadatos, hora cada segundo.
   13,6 MB sin comprimir y 2 MB comprimido (casi todo es terreno).
9. ~~Probar con jugadores y abrirlo en un visor~~ Hecho: grabado con un jugador en `runServer` y
   abierto en **Replay Mod original (Fabric 1.20.1)** en una instancia aparte. Se ve y se puede
   volar libremente. Queda por comprobar con el modpack completo en ReForgedPlay (contenido
   modded) y revisar en detalle explosiones y chat en una zona grande.

10. **Scoreboard y vida (2026-11-13):**
    - El scoreboard real ya se grababa durante la partida (pasa por `PlayerList.broadcastAll`).
      Ahora también su estado al empezar: equipos y objetivos que se muestran (lateral, TAB, bajo
      el nombre), igual que `PlayerList.updateEntireScoreboard`. Probado: entra al principio y los
      cambios posteriores se graban solos.
    - Minecraft **no envía** los objetivos que no se muestran en ningún sitio, así que esos no están
      en el replay.
    - **Vida de cada jugador** bajo su nombre («18 ❤», en medios corazones como el criterio `health`
      de vanilla). Es un objetivo `wwrp2_vida` que **solo existe en el replay**, no en la partida
      real. Se graba solo cuando la vida cambia. La armadura se descartó (no hay armaduras en el
      evento). Si el scoreboard real usa el hueco «bajo el nombre», en el replay manda el nuestro
      hasta que el real cambie.
    - Pendiente de ver con jugadores: la vida no se puede probar sin ellos.

### Detalle de la fase 2: rondas (2026-11-14)

- `ReplayRounds` escucha `RoundPhaseChangeEvent` de la feature `rondas` (sin tocar `rondas`).
- **Empieza** al pasar a Preparación con el archivo `ronda-<N>_<fecha>.mcpr`. **Para** cuando la
  ronda termina, tanto si se juega entera como si se corta con `/ronda parar`.
- **La ronda manda:** si había una grabación manual de zona, se para (queda guardada) y empieza la de
  la ronda.
- Si un admin hace `/replay stop` a mitad de ronda, se respeta: al terminar la ronda no se toca nada,
  aunque haya otra grabación manual en marcha.
- Avisos en el chat solo para los OP: «grabando la ronda N», «guardándose en …» y, en rojo, si no se
  puede grabar (por ejemplo, porque no hay zona definida). Esos avisos no salen en el replay.
- El anuncio «[Ronda N] Preparación» no sale en el replay (se manda justo antes de empezar a grabar);
  el de fin de ronda sí.
- Probado con RCON: manual → ronda (la manual se guarda), ronda completa, ronda cancelada y
  `/replay stop` a mitad de ronda. Todo con los archivos y nombres esperados.

### Detalle de la fase 3: marcadores (2026-11-16)

Decidido con Mistic: automáticos de kills, muertes sin asesino y cambios de fase; el admin solo
edita la grabación en curso (los replays guardados se retocan en el propio Replay Mod); la cámara
del marcador encuadra la kill. Multikills y rachas quedan abiertos para el futuro.

- **Kills** (`ReplayMarkers`, `LivingDeathEvent` con prioridad mínima y sin cancelados): solo
  jugadores muertos dentro de la zona. `Mistic → Pepe`. Si la muerte es indirecta (caída tras un
  golpe, lava...), Minecraft se la atribuye al último jugador que pegó: `Mistic → Pepe (caída)`.
- **Muertes sin jugador:** `Pepe murió (caída)` o `Pepe murió (Zombie)`. Las causas comunes están
  en castellano; el resto sale con el id de Minecraft.
- **Cámara de la kill:** 6 bloques por detrás y 3 por encima de la víctima, mirando a su pecho.
- **Cambios de fase** (`ReplayRounds`): `Ronda 2: Preparación`, `Combate`, `Finalización` y
  `terminada`/`cancelada`. Van a la posición inicial de la cámara. También en grabaciones manuales.
- **Comandos:** `/replay marca nueva|lista|editar|borrar` (ver README). El marcador del admin se pone
  donde está mirando él; desde consola, en la posición inicial de la cámara. Los números son los de
  `lista` y se pueden editar y borrar también los automáticos.
- **Colores** (`MarkerType`, pedido por Mistic): verde las fases (inicio, cambios, fin), rojo las
  kills de jugador (directas o indirectas), naranja lo PvE (mobs y entorno) y azul los manuales.
  El visor es **ReForgedPlay** (port de Replay Mod; Replay Mod en Fabric solo se usa para probar
  mientras tanto). Ninguno de los dos **tiene color de marcador** (el `Marker` de Replay Mod solo guarda nombre, tiempo y posición), así
  que el color va como código `§` al principio del nombre (Minecraft lo pinta al mostrar el texto)
  y como campo propio `wwrp2_tipo`, que ReForgedPlay ignora y usará el visor de la fase 4. El icono
  del marcador en la línea de tiempo sigue siendo igual para todos. Si Replay Mod reescribe los
  marcadores (al editarlos en él), `wwrp2_tipo` se pierde pero el código del nombre se queda.
- `markers.json` y `metaData.json` se escriben **solo en ASCII** (tildes y `§` como escapes
  unicode de JSON, la «ó» se escribe `\u00f3`): Replay Mod (y por tanto ReForgedPlay) los lee con la codificación del sistema, que en Windows con Java 17 no es
  UTF-8, y si no las tildes saldrían rotas.
- Probado con RCON: marcadores de fase, añadir, editar, borrar, número inexistente y `markers.json`
  final correcto (UTF-8). **Falta probar kills y muertes con jugadores.**

### Detalle de la fase 4a: visor básico (2026-11-16)

Decidido con Mistic: solo admins (permiso 2) y solo fuera de ronda; si empieza una ronda, quien
esté viendo un replay vuelve solo al mundo. Diseño copiado de ServerReplay (MIT, rama 1.20.1),
reescrito en Java para Forge y sin dependencias nuevas (`ReplayViewer`).

- **Salir del mundo real:** se quita al espectador del nivel (como en un cambio de dimensión,
  `removePlayerImmediately` + `revive`, así las capabilities de Forge siguen vivas) y de la lista de
  jugadores. Los demás lo ven irse del TAB y desaparecer.
- **Aislamiento con Netty, sin Mixin en la conexión:** un handler antes de `packet_handler` tira todo
  lo que el servidor real le manda (menos keep-alive y desconexión) y todo lo que manda su cliente
  (movimiento, clics, inventario, chat), salvo keep-alive, ajustes, canales de registro y los
  comandos del visor.
- **Reproducción:** un hilo propio lee `recording.tmcpr` del `.mcpr` y manda los paquetes ya
  codificados directamente por debajo del `encoder`, con sus tiempos, velocidad y pausa. Se salta el
  «Login Success». Si el cliente va lento (terreno inicial) espera en vez de llenar la memoria.
- **UUID del espectador:** si sale en el replay (jugó esa ronda), se le cambia el UUID en la lista
  de jugadores y al aparecer, porque el cliente no puede tener otra entidad con su propio UUID.
- **Comandos dentro del visor** (los únicos que funcionan): `/replay pausa`, `/replay velocidad <x>`
  (0,25–8), `/replay reiniciar` y `/replay salir`. Barra de jefe con nombre, tiempo, velocidad y pausa.
- **Volver:** se manda lo mismo que `PlayerList.placeNewPlayer` (Join Game real, recetas, tags,
  permisos y comandos, scoreboard, lista de jugadores, nivel, barras, efectos, inventario y vida)
  sin crear otro jugador, y vuelve al sitio exacto.
- **Desconectarse viendo un replay:** se limpia y el servidor guarda al jugador con la posición de
  antes de entrar al replay.
- **Ventaja frente a ReForgedPlay:** el cliente ya tiene los IDs del modpack de este servidor, así
  que el riesgo del handshake de Forge no aplica.
- **Pendiente de probar con cliente:** entrar, ver, pausar, velocidad, reiniciar, salir, que todo
  vuelva bien (inventario, TAB, scoreboard, otros jugadores) y desconectarse viendo un replay.
- **Riesgo:** mods que sincronizan datos al entrar o al cambiar de dimensión pueden no reenviarlos al
  volver; se mira con el modpack completo.

**Limitaciones conocidas de la fase 1:**
- `/say` y otros mensajes de chat «de sistema firmados» no se graban (sí el chat normal y las
  muertes). Se mira si hace falta.
- Lo que un jugador recibe solo para él (su vida, su inventario) no está en el replay de zona.
- Tamaño estimado del terreno de un mapa 1000×1000 (~4.000 chunks): ~135 MB sin comprimir y ~20 MB
  comprimido, solo al empezar. Se mide de verdad en la fase 5.
- El mapa debe estar ya generado: al empezar se cargan todos los chunks de la zona (≈10 ms por tick).
- Contenido modded: los IDs de bloques e items de otros mods dependen del registro de Forge; el
  replay no incluye el handshake de Forge. Hay que comprobarlo al probar con el modpack completo.

## Decisiones pendientes

- [x] **Añadir Mixin a `build.gradle`** — aprobado por el admin y aplicado el 2026-11-12. Verificado:
  `./gradlew build` pasa, el manifiesto lleva `MixinConfigs: wwrp2.replay.mixins.json` y en
  `runServer` Mixin registra la config sin errores. Detalle original de la propuesta: (plugin `org.spongepowered.mixin` para generar el refmap,
  procesador de anotaciones y el atributo `MixinConfigs` en el manifiesto del jar). El runtime de
  Mixin ya viene con Forge. Necesita el permiso del responsable de la feature y, por tocar algo
  compartido, conviene avisar al equipo. **Alternativa sin Mixin:** subir `view-distance` a 32 para
  todos y aceptar que los proyectiles y mobs lejanos al cámara no se graben.

## Riesgos

- **Fase 1:** los puntos de enganche de Mixin dependen de nombres internos de Minecraft. Se
  comprueban uno a uno con `javap` y compilando antes de darlos por buenos.
- **Fase 4:** es la más grande, más o menos igual que todo lo anterior junto. El punto delicado es
  aislar al espectador sin que el servidor real se líe con él (posición, IDs de entidades,
  inventario).
- **Tamaño:** estimación muy gruesa de varias decenas de MB por ronda de 20 minutos con 20
  jugadores. Se mide en la fase 5.
