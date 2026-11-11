# Cómo trabajamos en wwrp-2

## Requisitos

- **JDK 17 instalado.** No hace falta que sea el Java por defecto: `gradle/gradle-daemon-jvm.properties` hace que Gradle lo busque y lo use aunque en el PATH haya otra versión (Gradle 8.8 no funciona con Java 23 o superior). Si no encuentra ninguno, el build falla avisando de que falta un JDK 17.

## Ramas

- **Una funcionalidad por rama, una persona responsable.** La rama sale de `develop` y se llama `feature/<nombre>`, por ejemplo `feature/kill-heal`.
- **Las ramas de feature solo existen en local: no se suben al remoto.** Al remoto solo se suben `develop` y `master`. Si GitHub propone abrir un pull request, se ignora.
- **`develop`** recibe las features terminadas, aprobadas por el equipo y que compilan.
- **`master`** solo recibe merges desde `develop` (mejor si van etiquetados con versión, p. ej. `v1.0.0`) y solo con versiones completas del proyecto. Si más adelante se añaden features o arreglos, se hace una versión nueva que sale de `develop`.

Para empezar una feature:

```bash
git checkout develop
git pull
git checkout -b feature/mi-feature
```

Cuando la feature está terminada y aprobada, se mergea en local y solo se sube `develop`:

```bash
git checkout develop
git pull
git merge --no-ff feature/mi-feature
./gradlew build                      # develop tiene que compilar antes de subirlo
git push origin develop
git branch -d feature/mi-feature
```

## Estructura del código

```
src/main/java/com/wwrp2/
├── Wwrp2.java             punto de entrada del mod (no se toca por feature)
└── feature/
    └── killheal/          una feature = un paquete
        ├── ...            lógica común (servidor y cliente)
        └── client/        solo cliente: render, HUD, teclas...
```

1. **Todo el código de una feature va en `com.wwrp2.feature.<nombre>`**, que es el nombre de la rama sin guiones: `feature/kill-heal` → `com.wwrp2.feature.killheal`. Una rama no toca los paquetes de otras features.
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
5. **Código compartido** entre varias features va en `com.wwrp2.util`, no dentro de una feature.
6. **Recursos compartidos** (por ejemplo `assets/wwrp2/lang/es_es.json`): las claves llevan el nombre de la feature, como `wwrp2.killheal.mensaje`, para no pisarse.

## Antes de mergear en `develop`

- [ ] `./gradlew build` termina bien.
- [ ] Probado en el juego con `./gradlew runClient` (y `./gradlew runServer` si toca lógica de servidor).
- [ ] Solo cambia su paquete, sus recursos y, si hace falta, su línea en `Wwrp2.java`.

## Trabajar con IA

Todos programamos con IA. Al empezar cada conversación con la IA, pega este prompt antes de tu primera petición:

```text
Vas a ayudarme a programar wwrp-2, un mod de Minecraft Forge para 1.20.1 (Forge 47, mappings oficiales de Mojang, Java 17). Antes de nada, lee CONTRIBUTING.md: son las normas del equipo y tienes que seguirlas siempre.

Antes de empezar:
1. Pregúntame mi nombre y el nombre de la feature. No te los inventes y no empieces hasta que te conteste.
2. Crea la rama local desde develop: `git checkout develop`, `git pull`, `git checkout -b feature/<nombre-de-la-feature>`. Si ya existe una rama para esa feature, pregúntame si seguimos en ella.
3. El código va en `com.wwrp2.feature.<nombre sin guiones>`, y lo que solo existe en el cliente, en su subpaquete `client`. Pon mi nombre con `@author` en las clases nuevas.

Mientras desarrollas:
- Usa solo APIs de Forge 47 y Minecraft 1.20.1. Nada de NeoForge ni de otras versiones; si dudas de una firma, compila para comprobarla.
- Toca solo el paquete y los recursos de esta feature. Si necesitas cambiar algo compartido (`Wwrp2.java`, `build.gradle`, `gradle.properties`, `mods.toml`, `util` o código de otra feature), dime qué y por qué, y espera a que te diga que sí.
- No añadas dependencias sin preguntarme.
- Comentarios y mensajes de commit en español. Los commits van en la rama de la feature, nunca directamente en develop ni en master.
- Comprueba con `./gradlew build` que compila.

Al terminar:
1. Cuando la feature esté desarrollada y compile, dime qué tengo que probar en el juego (`./gradlew runClient`, o servidor y varios clientes si hace falta) y pregúntame si la pasamos a develop. No hagas el merge sin un sí explícito.
2. Si te digo que sí: `git checkout develop`, `git pull`, `git merge --no-ff feature/<nombre-de-la-feature>` y `./gradlew build`. Si hay conflictos, para y explícamelos antes de resolverlos.
3. Pregúntame si subo develop (`git push origin develop`) y no lo subas sin un sí. Después borra la rama local con `git branch -d feature/<nombre-de-la-feature>`.

Nunca:
- Subas la rama de feature al remoto ni abras pull requests: las ramas de feature son solo locales.
- Toques master: solo recibe versiones completas desde develop, cuando el equipo lo decida.
```
