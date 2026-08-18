# NeoPal — mascota virtual para Android

Tamagotchi completo en **Kotlin + Jetpack Compose**, con estética de consola híbrida moderna:
chasis oscuro, rieles laterales neón y pantalla con bisel. Todo el arte —criatura, escenarios,
partículas e iconos— se **dibuja por código con vectores**, y todo el audio se **sintetiza en
tiempo real**: el proyecto no incluye un solo PNG ni un solo WAV.

La lista completa de features está en **[docs/FEATURES.md](docs/FEATURES.md)**.

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
                      ↓                                            ↑
                 PetRepository  ←────── DataStore + JSON ──────────┘
                      ↑
                 CareWorker (WorkManager, cada 15 min)
```

El **dominio es puro**: `Simulation` y `CareActions` no conocen Android, reciben un estado y
devuelven uno nuevo. Eso permite que el mismo código corra en el bucle de primer plano, al
volver de segundo plano y dentro del worker, y que se pueda testear con JUnit plano.

El estado completo se guarda como **un solo blob JSON**. Campos nuevos toman su valor por
defecto y los desconocidos se ignoran, así que el esquema puede crecer sin migraciones.

## Cómo se juega

- Toca a la criatura para acariciarla; mantenla pulsada para guardar una foto en el álbum.
- Los botones del riel derecho funcionan: **A** acaricia, **X** juega, **Y** abre la despensa;
  **–** ajustes y **+** estadísticas.
- Un día de mascota dura 20 minutos reales por defecto (ajustable de 2 a 60 en Ajustes).
- Cómo la críes decide en qué evoluciona: jugar y ganar → *Athletic*; comer de más → *Gourmand*;
  disciplina y elogios → *Scholar*; descuidarla → *Feral*.
- Si muere, la siguiente generación hereda monedas, cosméticos, logros y el álbum.

## Marcas

El estilo es el de un handheld genérico. No se usa ningún nombre, logo, tipografía ni forma
registrada de terceros, y todo el arte es original.
