# Cómo trabajamos en wwrp-2

## Requisitos

- **JDK 17 instalado.** No hace falta que sea el Java por defecto: `gradle/gradle-daemon-jvm.properties` hace que Gradle lo busque y lo use aunque en el PATH haya otra versión (Gradle 8.8 no funciona con Java 23 o superior). Si no encuentra ninguno, el build falla avisando de que falta un JDK 17.

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
src/main/java/com/daniel/wwrp2/
├── Wwrp2.java             punto de entrada del mod (no se toca por feature)
└── feature/
    └── killheal/          una feature = un paquete
        ├── ...            lógica común (servidor y cliente)
        └── client/        solo cliente: render, HUD, teclas...
```

1. **Todo el código de una feature va en `com.daniel.wwrp2.feature.<nombre>`**, que es el nombre de la rama sin guiones: `feature/kill-heal` → `com.daniel.wwrp2.feature.killheal`. Una rama no toca los paquetes de otras features.
2. **Lo que solo existe en el cliente** (renderizado, pantallas, HUD, teclas) va en el subpaquete `client/`. En un servidor dedicado esas clases de Minecraft no existen y lo harían fallar.
3. **Cada feature se registra sola**, sin tocar `Wwrp2.java`:

   ```java
   // Eventos del juego (bus de Forge), en servidor y cliente
   @Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID)
   public final class MiFeatureEvents {
       @SubscribeEvent
       public static void onAlgo(LivingDeathEvent event) { ... }
   }
   ```

   - Solo cliente: `@Mod.EventBusSubscriber(modid = Wwrp2.MOD_ID, value = Dist.CLIENT)`.
   - Eventos de carga del mod (p. ej. `FMLClientSetupEvent`): añade `bus = Mod.EventBusSubscriber.Bus.MOD`.
4. **Única excepción:** si la feature registra items, bloques, etc. con `DeferredRegister`, añade una sola línea en el constructor de `Wwrp2`, en el sitio marcado (`MiFeatureRegistry.register(modEventBus);`).
5. **Código compartido** entre varias features va en `com.daniel.wwrp2.util`, no dentro de una feature.
6. **Recursos compartidos** (por ejemplo `assets/wwrp2/lang/es_es.json`): las claves llevan el nombre de la feature, como `wwrp2.killheal.mensaje`, para no pisarse.

## Antes de pedir el merge a `develop`

- [ ] `./gradlew build` termina bien.
- [ ] Probado en el juego con `./gradlew runClient` (y `./gradlew runServer` si toca lógica de servidor).
- [ ] Solo cambia su paquete, sus recursos y, si hace falta, su línea en `Wwrp2.java`.
