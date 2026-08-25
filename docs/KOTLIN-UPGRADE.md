# ¿Puede este proyecto subir de Kotlin?

Evidencia y una recomendación. **No es la actualización**: no se tocó `libs.versions.toml`, ni
`build.gradle.kts`, ni se volvió a añadir la dependencia. Decidir es otro paso.

La pregunta viene de que `com.google.ai.edge.litertlm:litertlm-android:0.12.0` puso CI en rojo en
`:app:compileDebugKotlin` con trece errores de la misma forma:

```
Module was compiled with an incompatible version of Kotlin.
The binary version of its metadata is 2.3.0, expected version is 2.0.0.
```

El artefacto se descargó bien. No es un problema de resolución: es que la librería está compilada
con un Kotlin más nuevo del que este proyecto habla. El motor se revirtió en `0d8adce`; las capas
puras se quedaron.

---

## 1. El objetivo, medido y no recordado

La versión de metadatos de un jar se lee del propio artefacto: está en la anotación `@Metadata`
de cada clase. Se bajaron los `kotlin-stdlib` de Maven Central y se leyó el campo `mv` con
`javap`, en vez de fiarse de la memoria:

| Kotlin | `mv` medido |
|---|---|
| 2.0.21 | `[1,9,0]` |
| 2.1.0 · 2.1.21 | `[2,1,0]` |
| 2.2.0 · 2.2.10 · 2.2.20 · 2.2.21 | `[2,2,0]` |
| 2.3.0 · 2.3.10 · 2.3.21 | `[2,3,0]` |
| 2.4.0 | `[2,4,0]` |

> El `[1,9,0]` de 2.0.21 no es una errata: la stdlib se publica compilada con language version 1.9
> por compatibilidad. Es la única fila donde `mv` no sigue a la versión del compilador, y conviene
> saberlo antes de sacar la regla general de una sola muestra.

Así que **metadatos 2.3.0 = compilado con Kotlin 2.3.x**. Lo más bajo que *emite* 2.3.0 es 2.3.0.

Pero la pregunta que importa no es qué versión *emite* 2.3.0, sino cuál lo *acepta*, y no son la
misma. El compilador de Kotlin tolera metadatos de **un minor por delante**. Eso se midió, no se
supuso: se puso un jar con `mv=[2,3,0]` en el classpath de compilación de cada versión y se miró
si compilaba.

| Compilador | Ve una librería con metadatos 2.3.0 | Ve una con 2.4.0 |
|---|---|---|
| 2.0.21 | **rechaza** — reproduce el error de CI literal | — |
| 2.1.21 | **rechaza** (`expected version is 2.1.0`) | — |
| 2.2.0 | acepta | — |
| 2.2.21 | acepta | **rechaza** (`expected version is 2.2.0`) |
| 2.3.0 | acepta | — |
| 2.3.21 | acepta | acepta |

Dos sondas independientes dan lo mismo: una con la propia `kotlin-stdlib` 2.3.21 como librería
ajena, y otra —más estricta, porque el compilador trata la stdlib de forma especial— con la stdlib
de su *propia* versión en el classpath y `kotlin-reflect` 2.3.21 encima, que es exactamente la
situación en la que litertlm pone a CI.

**El suelo real es Kotlin 2.2.0, no 2.3.0.** Y la última fila de la tabla es la razón para no
quedarse ahí: 2.2.x consume esa librería sólo de prestado, y el préstamo se acaba en cuanto Google
recompile litertlm con Kotlin 2.4.

> Vale la pena decir que el banco de pruebas **reproduce el fallo de CI exacto**, con el mismo
> texto y el mismo número, antes de decir que algo lo arregla. Es la regla de `docs/PLAN.md`: un
> control local no vale por lo que compila, vale por lo que rechaza igual que CI.

### AGP y Gradle: no hay que tocarlos

AGP no está en Maven Central (404) y Google Maven está bloqueado, así que el jar de AGP no se
puede leer desde aquí. Pero **el plugin de Gradle de Kotlin sí está en Central**, y lleva dentro el
mínimo de AGP que soporta. Se leyó con `javap` del inicializador estático de
`AgpCompatibilityCheck`:

| Plugin de Kotlin | AGP mínimo | Gradle mínimo |
|---|---|---|
| KGP 2.2.21 | **7.3.1** | 7.6.3 |
| KGP 2.3.21 | **8.2.2** | 7.6.3 |

El proyecto está en AGP **8.7.2** y Gradle **8.10.2**: por encima de los dos mínimos en ambos
casos. Y no existe ningún diagnóstico de «AGP demasiado *nuevo*» — sólo `IncompatibleAgpVersion`
**TooLow**, de severidad `FATAL`. Del lado de Kotlin, AGP 8.7.2 vale tal cual.

**No hace falta subir AGP.** Es la mejor noticia del documento, porque subir AGP arrastra
compileSdk, R8 y el manifiesto, y eso sí sería una obra.

---

## 2. Lo que sí se pudo verificar aquí

Se copiaron los cuatro bancos de pruebas del scratchpad apuntando a este árbol, con su propio
directorio de salida y con los jars de la versión nueva bajados de Maven Central. **Lo único que
cambia entre columnas es la versión del compilador**: mismas fuentes, mismos flags, mismo
classpath, misma división en dos etapas de coroutines 1.8.1 / 1.9.0.

| Banco | Qué compila | 2.0.21 (base) | 2.2.21 | 2.3.21 |
|---|---|---|---|---|
| `mindtest` | dominio + cliente remoto + sus tests | **OK (734)** | **OK (734)** | **OK (734)** |
| `gametest` | los 7 juegos + `GameChrome` + dominio | **OK (38)** | **OK (38)** | **OK (38)** |
| `arttest` | el `CreatureArt.kt` real en un recorder | **OK (15)** | **OK (15)** | **OK (15)** |

787 tests, tres compiladores, cero errores. La etapa 1 de `mindtest` —la compuerta dura que imita
`:app:compileDebugKotlin` contra coroutines 1.8.1— queda limpia en las tres.

### Los avisos nuevos

Con `-nowarn` quitado, para poder contarlos:

- **2.2.21: idéntico a la base.** Un solo aviso, el mismo de siempre
  (`'constructor(p0: String!): URL' is deprecated`, en `RemoteMindClient.kt:240`).
- **2.3.21: cuatro avisos nuevos, todos benignos y todos en ficheros de test.** Son de análisis de
  nulabilidad mejorado, que ahora ve como redundante lo que antes no:
  - `SoloPlayTest.kt:97` — `unnecessary safe call on a non-null receiver of type 'Activity'`
  - `WidgetSnapshotTest.kt:289-291` — `unnecessary non-null assertion (!!)` (×3)
  - `NpGameReflect.kt:37` — `java.util.List` no recomendado, usar `kotlin.collections.List`

Ninguno es un error, y **nada en el build pone `allWarningsAsErrors`** (se comprobó en
`app/build.gradle.kts`, el `build.gradle.kts` raíz y `gradle.properties`), así que ninguno puede
ponerse rojo solo.

### El cuarto banco no sirve para esta pregunta, y hay que decirlo

`wtree/run.sh` compila *todo* `app/src/main/java` sin androidx y compara conjuntos de tipos de
mensaje entre dos árboles. Aquí no vale, y el motivo es el mismo que ya está anotado en `PLAN.md`
para el classpath: **cambiar el compilador invalida la línea base igual que cambiarlo de
classpath**. Su base son ~13.000 errores de cascada, y entre 2.0 y 2.3 el compilador cambió cómo
*redacta* los tipos de recuperación de errores (`ERROR CLASS: Symbol not found for Color` pasó a
`??? (Unresolved qualified name: Color)`). El diff sale lleno de «tipos nuevos» que son la misma
cascada escrita de otra forma.

Se corrió igual, y produjo un candidato que parecía real:

```
CreatureArt.kt:602:33: error: this declaration needs opt-in ... '@kotlin.ExperimentalStdlibApi'
```

Se leyó a mano, que es para lo que existe el banco. Es cascada: la línea es
`Color.White.copy(alpha = ...)` con `Color` sin resolver, y 2.3 resuelve ese `.copy` contra otra
cosa experimental. **El mismo fichero compila con cero errores y cero avisos bajo 2.3.21 en
`arttest`**, donde sí hay un `Color`. Falsa alarma, confirmada por el otro banco.

---

## 3. Lo que **no** se puede verificar aquí

Esto es la mitad que importa, y merece ser concreta en vez de un encogimiento de hombros.

**1. El plugin del compilador de Compose.** Es el riesgo mayor, por una razón estructural: en
`libs.versions.toml` el plugin es `kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose",
version.ref = "kotlin" }`. **Su versión no se elige, se deriva de la de Kotlin**: subir Kotlin
cambia el compilador de Compose quiera uno o no, mientras `composeBom` se queda en `2024.10.01`.
Y aquí no se puede correr: el `@Composable` de los bancos es una anotación pelada escrita a mano
en `ax/gstubs/runtime.kt`, sin `Composer`, sin `startRestartGroup`, sin sistema de snapshots — el
plugin no tiene contra qué bajar su IR. Lo que **sí** se pudo medir del jar del plugin:

- La compuerta gruesa pasa: el `VersionChecker` de 2.3.21 declara un runtime mínimo de **1.0.0**,
  y el bom de 2024.10.01 da 1.7.x. Pero ese mínimo lleva años en 1.0.0; que pase no dice mucho.
- Lo que sí dice algo: el plugin de 2.2/2.3 referencia una **tercera raíz de paquete que el de
  2.0.21 no menciona nunca**, `androidx.compose.runtime.tooling`. Si el runtime 1.7.x trae lo que
  busca ahí, no se puede comprobar: `compose-bom` en Central devuelve **404**.

**2. El lado de AGP.** Sólo se pudo leer la dirección KGP→AGP. `com.android.tools.build:gradle`
no está en Central y Google Maven está bloqueado, así que **lo que AGP 8.7.2 espera del plugin de
Kotlin no se ha leído**. Que KGP acepte AGP 8.7.2 no demuestra que AGP 8.7.2 acepte KGP 2.3.

**3. Todo lo que necesita los artefactos androidx de verdad.** Es decir, todo `ui/`, `data/` y
`work/` menos los dos ficheros puros: las pantallas, los widgets, la voz, el actualizador, el
`PetViewModel`. Aquí o se compila contra transcripciones o no se compila. El precedente está
escrito en `PLAN.md` y costó siete errores en CI: **un sustituto puede tener el conjunto de firmas
perfecto y aun así aceptar código que el artefacto real rechaza** — al `DrawScope` transcrito le
faltaba `@DslMarker`, que no es una firma sino una anotación, y cambia lo que compila.

**4. Ninguna fase de Gradle corre aquí.** Sin SDK de Android y sin el jar de AGP, el script de
build nunca se configura. En concreto no se puede saber si `android { kotlinOptions { jvmTarget =
"17" } }` sigue configurando: esa superficie del DSL lleva moviéndose desde 2.0, y KGP 2.3.21 ya
marca `org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile` con `DeprecationLevel.ERROR`
(«Moved into API artifact»). Puede que no afecte a este build; **no se sabe desde aquí**.

**5. La librería en sí.** `litertlm-android:0.12.0` no se ha descargado nunca en este entorno. Que
sus metadatos sean 2.3.0 se sabe **sólo por el texto del error de CI**, no por haber leído el jar.
Su `minSdk` declarado sigue sin verificar, igual que en `CEREBRO-LOCAL.md`.

**6. Nada en ejecución.** R8 y `isMinifyEnabled` en release, el APK, el teléfono. La primera
medición de verdad sale de CI, y la segunda de un teléfono.

---

## 4. Qué se rompe si sale mal

CI corre, en un solo job y en orden: `:app:testDebugUnitTest` → `:app:assembleDebug` → publicar el
release rodante `debug-latest`.

Lo primero, con precisión, porque es menos grave de lo que parece: **el APK publicado no
desaparece**. El paso que borra y recrea el release (`gh release delete debug-latest --yes
--cleanup-tag`) va *después* de los dos de build, y los pasos son secuenciales: si el toolchain se
rompe, el job muere en los tests y nunca llega a borrar nada. Lo que hay instalable sigue ahí.

Lo grave es lo otro: **deja de salir cualquier cosa nueva**. Cada commit posterior se para en el
mismo sitio, el `versionCode` no avanza, y el actualizador dentro de la app sólo puede contestar
«estás al día» para siempre. Y no es sólo la feature del modelo: los 787 tests, los siete juegos,
las tres pantallas retro y el juego entero viajan sobre el mismo toolchain. Un toolchain roto
cuesta todo lo demás, no la parte que se estaba intentando añadir.

---

## 5. Recomendación

**No hacerlo ahora, y no por esta feature.**

El razonamiento, en orden:

1. **El riesgo no está en el código Kotlin de este proyecto.** Eso quedó medido: 787 tests, tres
   compiladores, cero errores, cinco avisos benignos y ninguno fuera de ficheros de test. Si el
   problema fuera el lenguaje, la respuesta sería «adelante».
2. **El riesgo está entero en la mitad que no se puede probar aquí** — y es la mitad que ya ha
   puesto CI en rojo tres veces por este mecanismo exacto: el `@DslMarker` que faltaba, el banco
   que compilaba contra coroutines 1.9.0, el parámetro que git mezcló sin conflicto. Las tres
   veces la forma fue la misma: *lo local decía verde porque no estaba mirando lo que rompía*.
3. **La actualización arrastra el compilador de Compose sin preguntar** (`version.ref = "kotlin"`),
   contra un `composeBom` que se queda quieto. Compilador de Compose nuevo con runtime de Compose
   viejo es la incógnita más grande de la lista, y toca todas las pantallas del juego a la vez.
4. **El premio es una feature opcional de un juego que está completo sin ella.** Y por el propio
   `CEREBRO-LOCAL.md` §8, ni siquiera sabemos si se nota: nadie ha medido si el modelo de 557 MiB
   se distingue del de 3,41 GiB para frases de dos líneas. Se estaría arriesgando el pipeline
   entero por algo cuyo valor sigue sin medir.

Es la misma cuenta que ya está hecha en el proyecto para el cerebro remoto: lo opcional no puede
costar lo que ya funciona.

### Si aun así se hace, así

No como parte de la feature. Como un cambio propio, que CI pueda contestar solo:

1. **Kotlin 2.3.21, no 2.2.21**, aunque 2.2.0 sea el suelo medido. 2.2.x sólo lee esa librería por
   la tolerancia de un minor, y eso se evapora en cuanto litertlm se recompile con Kotlin 2.4 —
   medido: 2.2.21 rechaza `mv=[2,4,0]`, 2.3.21 lo acepta. 2.3.21 lee 2.3.0 de forma nativa y
   además guarda un minor de margen. Pagar dos veces por el mismo salto es peor que pagarlo una.
2. **AGP se queda en 8.7.2 y Gradle en 8.10.2.** Están por encima de los mínimos de KGP 2.3.21
   (8.2.2 y 7.6.3) y no hay tope por arriba. Un cambio menos.
3. **Un PR que cambie sólo `libs.versions.toml`, sin la dependencia de litertlm.** Así CI contesta
   una sola pregunta —«¿sigue compilando y pasando este proyecto bajo Kotlin 2.3.21?»— en vez de
   dos mezcladas. Si sale rojo, se sabe de qué. Añadir la librería es el PR siguiente.
4. **Subir `composeBom` en ese mismo PR o justo después**, para que compilador y runtime de Compose
   se muevan juntos en vez de separarse. Es la incógnita §3.1, y es la que se puede reducir.

Y una nota para el que lo haga: los avisos nuevos de 2.3.21 están en `SoloPlayTest.kt:97`,
`WidgetSnapshotTest.kt:289-291` y `NpGameReflect.kt:37`. Son cuatro líneas, se limpian en un
minuto, y conviene limpiarlas antes para que el CI del PR salga sin ruido y cualquier aviso que
aparezca sea de verdad nuevo.

---

## 6. Cómo reproducir esto

Los scripts están en `scratchpad/kupgrade/`, fuera del repositorio:

| Script | Qué hace |
|---|---|
| `mvprobe.sh` | Baja stdlibs de Central y lee su `mv` con `javap` — la tabla de §1 |
| `accept.sh` · `accept2.sh` · `accept3.sh` | Qué compilador acepta metadatos 2.3.0 / 2.4.0 |
| `mindtest-k.sh` · `gametest-k.sh` · `arttest-k.sh` | Los tres bancos, parametrizados por versión de Kotlin |
| `warndiff.sh` | El mismo compilado sin `-nowarn`, un renglón por tipo de aviso |
| `wtree-k.sh` | El árbol entero; **no válido entre versiones de compilador**, ver §2 |
| `agp.sh` | Saca el AGP mínimo del `AgpCompatibilityCheck` de KGP |
| `composecalls.sh` · `composediff.sh` | Qué símbolos del runtime de Compose referencia cada plugin |

Todos toman la versión como argumento y **2.0.21 es una de las opciones válidas**, que es lo que
hace comparables las columnas: la base se vuelve a medir con el mismo script, no se copia de un
run anterior.
