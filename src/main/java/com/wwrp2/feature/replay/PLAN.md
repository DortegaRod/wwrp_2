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
| **1. Grabador de zona** | Ver detalle abajo. | 🔧 En curso: Mixin configurado y `develop` traído |
| **2. Rondas** | Arranca solo al pasar a Preparación y para al terminar la ronda (`RoundPhaseChangeEvent`). Archivos `ronda-N_fecha.mcpr`. Los comandos manuales se mantienen. | |
| **3. Marcadores** | Automáticos: kills, muertes, rachas, cambios de fase. Comandos de admin para listar, añadir, editar y borrar, en grabaciones en curso y ya guardadas (`markers.json`). | |
| **4a. Visor básico** | `/replay ver <archivo>`. Aislar al espectador del mundo real, mandarle el replay con sus tiempos y ponerlo en vuelo libre. Al salir, volver a su sitio en el mundo real. | |
| **4b. Controles** | Items en la barra: pausa/play, velocidad (0,5×–4×), ±10 s. Saltar aplicando el último estado (idea de Hypixel). | |
| **4c. Highlights** | Menú de marcadores como capítulos para ir directo a cada kill. Seguir a un jugador en primera o tercera persona. | |
| **4d. Ver en grupo** | Varios espectadores en la misma sesión; un líder controla. | |
| **5. Escala** | Prueba con ~20 jugadores: tamaño, CPU y disco. Política de borrado de replays viejos. Más compresión solo si los números lo piden. | |
| **Extra** | Comprobar que el `.mcpr` abre en ReForgedPlay (vídeos cinematográficos). | Cuando se pueda |

### Detalle de la fase 1: grabador de zona

1. ~~Traer `develop` a la rama (incluye la feature `rondas`).~~ Hecho.
2. ~~Configurar Mixin.~~ Hecho: `settings.gradle`, `build.gradle` y `src/main/resources/wwrp2.replay.mixins.json`.
3. `/replay zona <esquina1> <esquina2>`: guarda la zona en la config del servidor.
4. Estado inicial al empezar: Login Success, Join Game, todos los chunks de la zona con luz,
   entidades presentes (con equipo, metadatos y pasajeros), hora, clima, borde del mundo, lista de
   jugadores.
5. Durante la grabación, mixins que copian al grabador lo que el servidor reparte en la zona:
   chunks que se cargan o descargan, cambios de bloques, entidades (todas, sin límite de
   distancia), sonidos, partículas, explosiones, chat y lista de jugadores.
6. Reutilizar `ReplayRecorder`: misma escritura y empaquetado que en la fase 0.
7. Añadir número de versión del formato propio en los metadatos.
8. Validar con el analizador del `.mcpr` (paquetes, tiempos, Join Game presente, chunks de la zona).

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
