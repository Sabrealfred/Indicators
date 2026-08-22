# NeoPal — mascota virtual para Android

Tamagotchi completo en **Kotlin + Jetpack Compose**, con estética de consola híbrida moderna:
chasis oscuro, rieles laterales neón y pantalla con bisel. Todo el arte —criatura, escenarios,
partículas e iconos— se **dibuja por código con vectores**, y todo el audio se **sintetiza en
tiempo real**: el proyecto no incluye un solo PNG ni un solo WAV.

No es sólo una mascota a la que se le aprieta un botón: **decide sola**. Tiene un cerebro de
utilidad que elige qué hacer y dice por qué, catorce genes continuos que se heredan y se mezclan,
una colonia donde conoce a otras y tiene crías, y lecciones que pasan de una generación a la
siguiente. Nada de eso necesita internet ni una clave. Si además le das un modelo de lenguaje,
conversa, elige entre las opciones que el juego ya validó y se pone sus propios mandados.

- Features: **[docs/FEATURES.md](docs/FEATURES.md)**
- Estado vivo de lo hecho y lo que falta: **[docs/PLAN.md](docs/PLAN.md)**
- Los 100 items del backlog: **[docs/IMPROVEMENTS.md](docs/IMPROVEMENTS.md)**

## Descargar el APK

Cada push a `main` o a una rama `claude/**` compila en GitHub Actions y refresca una
pre-release rodante con el APK de debug:

**https://github.com/Sabrealfred/Indicators/releases/tag/debug-latest**

Abrí ese link desde el celular, bajá `neopal-debug.apk` y permití la instalación desde
orígenes desconocidos. Está firmado con la clave de debug de Android: sirve para probar, pero
para publicar en Play Store hace falta un keystore de release propio.

## Cómo compilarlo

El primer build se hizo en CI (ver `.github/workflows/android.yml`), porque el entorno donde se
escribió el proyecto no tenía Android SDK. En local:

```bash
# Android Studio Ladybug o superior, JDK 17
./gradlew :app:assembleDebug     # requiere Android SDK 35 instalado
./gradlew :app:testDebugUnitTest # tests del dominio, sin emulador
```

O abrí la carpeta directamente en Android Studio y dale a Run. El wrapper de Gradle está
declarado en `gradle/wrapper/gradle-wrapper.properties`; si falta el `gradlew`, generalo con
`gradle wrapper --gradle-version 8.10.2`.

- `minSdk` 24 · `targetSdk`/`compileSdk` 35 · AGP 8.7.2 · Kotlin 2.0.21 · Compose BOM 2024.10.01

## Arquitectura

```
UI (Compose)  →  PetViewModel  →  CareActions / Simulation  →  PetState
                      ↓                 Brain / Errands           ↑
                      ↓                                           ↑
                 PetRepository  ←────── DataStore + JSON ─────────┘
                      ↑
                 CareWorker (WorkManager, cada 15 min)

                 RemoteMindClient  →  proxy propio o clave del jugador  (opcional)
```

El **dominio es puro**: `Simulation`, `CareActions` y `Brain` no conocen Android, reciben un
estado y devuelven uno nuevo. Eso permite que el mismo código corra en el bucle de primer plano,
al volver de segundo plano y dentro del worker, y que se pueda testear con JUnit plano.

La red vive **fuera** del bucle: el cerebro local decide primero y siempre, y el modelo —cuando
hay— opina después sobre una lista que el juego ya declaró legal, eligiendo por índice. Cada
elección se revalida en el momento de ejecutarse, porque una respuesta que viajó por la red
describe un mundo que ya se movió.

`./gradlew :app:testDebugUnitTest` corre ~280 tests, incluidos 16 que levantan un servidor HTTP
de mentira y ejercitan la ruta de red de verdad.

El estado completo se guarda como **un solo blob JSON**. Campos nuevos toman su valor por
defecto y los desconocidos se ignoran, así que el esquema puede crecer sin migraciones.

## El cerebro opcional

La mitad autónoma funciona sin nada. Encima de eso, en **Ajustes → Que piense**, se le puede dar
un modelo por dos caminos:

- **Tu propia clave** de [OpenRouter](https://openrouter.ai) (hay modelos gratis) — no dependés
  del límite de nadie.
- **Un servicio compartido**: `proxy/` tiene un Cloudflare Worker de un archivo, sin build, que
  guarda la clave del lado del servidor. Dos comandos y anda; ver **[proxy/README.md](proxy/README.md)**.

Sin clave y sin servicio, la criatura vive su vida entera igual: decide, aprende de sus padres,
se alimenta sola y cría. Lo único que se pierde es que hable.

## Cómo se juega

- Toca a la criatura para acariciarla — y **dónde** la tocás importa: la cabeza le gusta, la
  panza le da risa y la cola no. Pellizcá para acercarte (modo foto); mantenela pulsada para
  guardar una foto en el álbum.
- Los botones del riel derecho funcionan: **A** acaricia, **X** juega, **Y** abre la despensa;
  **–** ajustes y **+** estadísticas.
- Siete minijuegos. Cuatro son con la criatura y no contra ella: en las **Escondidas** se turnan
  los roles, en **Traer la pelota** ella aprende tu tiro y le ves la corazonada, el **Dueto** no
  tiene nota equivocada ni puntaje en pantalla, y el **Rompecabezas** lo puede resolver sola si es
  lo bastante lista — ahí mirarla jugar es el juego.
- Un día de mascota dura 20 minutos reales por defecto (ajustable de 2 a 60 en Ajustes).
- Cómo la críes decide en qué evoluciona: jugar y ganar → *Athletic*; comer de más → *Gourmand*;
  disciplina y elogios → *Scholar*; descuidarla → *Feral*.
- Si muere, la siguiente generación hereda monedas, cosméticos, logros y el álbum — y también
  **lecciones**: lo que la mató le enseña algo a su cría, así que el hijo es mejor que el padre.
- Los genes se mezclan de verdad: una cría se parece a los dos, y `stance` empujado hacia arriba
  a lo largo de varias generaciones lleva la silueta de bípeda a cuadrúpeda.

## Marcas

El estilo es el de un handheld genérico. No se usa ningún nombre, logo, tipografía ni forma
registrada de terceros, y todo el arte es original.
