# Cómo trabajamos en Warp

## Ramas

- **Una funcionalidad por rama, una persona responsable.** La rama sale de `develop` y se llama `feature/<nombre>`, por ejemplo `feature/kill-heal`.
- **`develop`** recibe las features terminadas, aprobadas por el equipo y que compilan.
- **`master`** solo recibe merges desde `develop` (mejor si van etiquetados con versión, p. ej. `v1.0.0`) y solo con versiones completas del proyecto. Si más adelante se añaden features o arreglos, se hace una versión nueva que sale de `develop`.

Para empezar una feature:

```bash
git checkout develop
git pull
git checkout -b feature/mi-feature
```

Antes de pedir el merge a `develop`:

```bash
./gradlew build
git push -u origin feature/mi-feature
```

## Estructura del código

```
src/main/java/com/daniel/warp/
├── Warp.java              punto de entrada del mod (no se toca por feature)
└── feature/
    └── killheal/          una feature = un paquete
        ├── ...            lógica común (servidor y cliente)
        └── client/        solo cliente: render, HUD, teclas...
```

1. **Todo el código de una feature va en `com.daniel.warp.feature.<nombre>`**, que es el nombre de la rama sin guiones: `feature/kill-heal` → `com.daniel.warp.feature.killheal`. Una rama no toca los paquetes de otras features.
2. **Lo que solo existe en el cliente** (renderizado, pantallas, HUD, teclas) va en el subpaquete `client/`. En un servidor dedicado esas clases de Minecraft no existen y lo harían fallar.
3. **Cada feature se registra sola**, sin tocar `Warp.java`:

   ```java
   // Eventos del juego (bus de Forge), en servidor y cliente
   @Mod.EventBusSubscriber(modid = Warp.MOD_ID)
   public final class MiFeatureEvents {
       @SubscribeEvent
       public static void onAlgo(LivingDeathEvent event) { ... }
   }
   ```

   - Solo cliente: `@Mod.EventBusSubscriber(modid = Warp.MOD_ID, value = Dist.CLIENT)`.
   - Eventos de carga del mod (p. ej. `FMLClientSetupEvent`): añade `bus = Mod.EventBusSubscriber.Bus.MOD`.
4. **Única excepción:** si la feature registra items, bloques, etc. con `DeferredRegister`, añade una sola línea en el constructor de `Warp`, en el sitio marcado (`MiFeatureRegistry.register(modEventBus);`).
5. **Código compartido** entre varias features va en `com.daniel.warp.util`, no dentro de una feature.
6. **Recursos compartidos** (por ejemplo `assets/warp/lang/es_es.json`): las claves llevan el nombre de la feature, como `warp.killheal.mensaje`, para no pisarse.

## Antes de pedir el merge a `develop`

- [ ] `./gradlew build` termina bien.
- [ ] Probado en el juego con `./gradlew runClient` (y `./gradlew runServer` si toca lógica de servidor).
- [ ] Solo cambia su paquete, sus recursos y, si hace falta, su línea en `Warp.java`.
