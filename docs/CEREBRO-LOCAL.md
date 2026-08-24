# Un cerebro dentro del teléfono

Diseño, no implementación. Nada de esto está construido todavía.

La petición: que la criatura tenga un modelo corriendo **en el propio teléfono**, y que cuando
haya internet pueda usar el grande. El presupuesto declarado es de hasta 5 GB de app.

---

## 1. La restricción que de verdad aprieta no es el disco

Cinco gigas de disco es holgado. **La RAM es lo que decide**, y es un número mucho más pequeño y
mucho menos negociable:

| Modelo | Fichero `.litertlm` | Tamaño exacto | | RAM de dispositivo que Google exige |
|---|---|---|---|---|
| **Gemma 3 1B-IT** int4 | `gemma3-1b-it-int4.litertlm` | 584.417.280 B | 557 MiB | **6 GB** |
| **Gemma 4 E2B-it** | `gemma-4-E2B-it.litertlm` | 2.588.147.712 B | 2,41 GiB | **8 GB** |
| **Gemma 4 E4B-it** | `gemma-4-E4B-it.litertlm` | 3.659.530.240 B | 3,41 GiB | **12 GB** |

> Estas cifras salen de la lista de modelos que la propia app de Google publica
> (`google-ai-edge/gallery`, `model_allowlists/1_0_19.json`), donde `sizeInBytes` es el tamaño
> exacto del fichero y `minDeviceMemoryInGb` es el suelo de RAM que Google declara.
>
> **Corrección.** La primera versión de este documento decía que E2B pesaba ~1,3 GB y corría en
> «prácticamente cualquier teléfono desde 2022». Las dos cosas eran falsas: pesa casi el doble y
> Google pide 8 GB. E4B pide **12 GB**, que no es «gama alta reciente» sino gama alta y punto. Esos
> números venían de resúmenes, no del artefacto — el error exacto contra el que avisa la sección 2
> de este mismo documento. Se corrigieron antes de escribir una sola línea de código, que es la
> única parte de esto que salió bien.

Android no te da la RAM del teléfono: te da un presupuesto por proceso, y lo hace cumplir matando
la app. Un modelo que pide 5 GB en un teléfono de 6 GB no va lento — **desaparece**, y para el
jugador eso es indistinguible de que la mascota se haya muerto. Es el peor fallo posible en este
juego concreto.

Así que «el mejor que haya» no puede significar un solo modelo. Significa **el mejor que este
teléfono pueda sostener de verdad** — y con los números reales encima de la mesa, eso son **tres**
escalones, no dos. El de 557 MiB no es un premio de consolación: es el único que llega a un
teléfono normal, y para una criatura que habla en frases de dos líneas puede que no se note la
diferencia. Medirlo es trabajo pendiente, no una conclusión.

## 2. Qué modelo

**Gemma 4, cuantizado int4 con QAT, en formato `.litertlm`.** Razones, en orden de peso:

1. **QAT, no cuantización posterior.** Gemma 4 se entrena *sabiendo* que va a correr en 4 bits, así
   que el int4 conserva calidad cercana a bfloat16 en vez de degradarse. Es la diferencia entre un
   modelo pequeño y un modelo grande estropeado.
2. **Multilingüe de serie**: 35+ idiomas soportados, 140+ preentrenados. El español importa aquí y
   no es un añadido.
3. **Es el camino que Google usa.** Aquí hay que ser preciso, porque la primera versión de esto
   afirmaba de más: **no he podido leer** ninguna nota oficial de obsolescencia de MediaPipe
   `tasks-genai` — la página está bloqueada desde este entorno y no hay copia en archivo. Lo que
   **sí** está verificado es la conducta: la propia app de Google ya no depende de `tasks-genai`
   en absoluto, y las descripciones de sus modelos pasaron de «listo para Android usando la
   MediaPipe LLM Inference API» a «…usando LiteRT-LM» entre dos versiones. Es indicio fuerte, no
   una cita. No se pondrá una cita entrecomillada en ningún comentario hasta poder leerla.

```kotlin
// En google(), no en mavenCentral() — Central devuelve 404 para estas coordenadas.
// Versión fijada, no `latest.release`: una versión móvil es exactamente cómo se consigue una
// build roja por sorpresa, y la forma con argumentos con nombre de `sendMessageAsync` no compila
// en las versiones viejas. 0.12.0 está probada en un fichero de build del propio Google.
implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
```

**Sólo `arm64-v8a`.** La librería trae binarios nativos para `android_arm64` y `android_x86_64` y
nada más — ARM de 32 bits no está soportado, y las apps de ejemplo de Google filtran a `arm64-v8a`.
Eso hay que declararlo en el build y decirlo en pantalla, no descubrirlo en un teléfono viejo.

**Se empaquetan los dos.** No como una pregunta al jugador —«¿ligero o bueno?» no es una pregunta
que nadie pueda responder— sino como una medición: se lee la memoria disponible del proceso y se
ofrece E4B sólo donde cabe, E2B donde no, y nada donde tampoco. El jugador puede forzar el
pequeño si prefiere gastar menos batería; no puede forzar el grande en un teléfono que lo va a
matar.

> **Verificado desde las fuentes de Google en GitHub**, porque Google Maven, Hugging Face y
> `ai.google.dev` están todos bloqueados desde este entorno: las coordenadas Maven, el repositorio
> (`google()`), las ABI, la superficie real de la API (`Engine`, `EngineConfig`, `Conversation`,
> `sendMessageAsync` devolviendo un `Flow<Message>`, todo `AutoCloseable`), los tres ficheros y sus
> tamaños exactos, y la forma de la URL de descarga.
>
> **Sigue sin verificar, y se dice en vez de taparse:** la versión más reciente publicada en Google
> Maven (el `maven-metadata.xml` es inalcanzable), el `minSdk` declarado del artefacto, si estos
> repositorios concretos de Hugging Face están *gated*, y el texto de la cláusula de
> redistribución de los términos de Gemma. Nada de eso se decide de memoria, así que nada de eso
> se ha decidido.

## 3. Por qué la arquitectura ya encaja

`MindProvider` ya es una interfaz con dos implementaciones: `RemoteMindClient` y el cerebro de
utilidad local. Una tercera entra sin tocar nada del juego.

Y hay una razón de fondo por la que añadir un modelo pequeño es **seguro**: las decisiones vuelven
como un **índice** sobre una lista que el juego ya validó, y se revalidan al ejecutarse. Un modelo
de 2 B devuelve basura mucho más a menudo que uno de 70 B — y esa ruta ya trata la basura como
«no», cayendo al cerebro local sin decir nada. La red se tejió para el modelo remoto y sirve igual
para el de casa. Lo mismo con `flatten()`, el tope de respuesta y el presupuesto de tiempo.

## 4. El enrutado, y por qué «si hay internet, el grande» está mal

Esa regla suena bien y produce un juego peor. El modelo remoto no es simplemente *mejor*: es más
lento, gasta cuota, y falla de maneras que el local no (DNS, 429, un proxy caído). El local es
instantáneo y gratis. Enrutar por conectividad tira esa ventaja a la basura.

La regla que propongo enruta por **qué se está preguntando**:

| Qué | Quién responde | Por qué |
|---|---|---|
| Una línea al tocarla, un comentario al azar, la charla | **Siempre el local** | Tiene que salir en el acto. Una frase de dos líneas no necesita 70 B, y esperar dos segundos por ella rompe el juguete. |
| Una respuesta en la pantalla de hablar | **Local primero, remoto si está** | El local escribe mientras el remoto piensa; si el remoto llega y es mejor, sustituye. Si no llega, nadie se entera. |
| Un plan, destilar la lección de una vida, una decisión con consecuencias | **Remoto si hay; local si no** | Son las tres cosas que de verdad se benefician de un modelo grande, y las tres toleran segundos. |
| Cualquier cosa en el bucle autónomo | **Ninguno de los dos modelos** | Ver abajo. Esto no es una preferencia, es un límite. |

Es decir: el modelo local no es un sustituto degradado del remoto. Es **el que contesta rápido**, y
el remoto es el que contesta bien. Cada uno hace lo que hace mejor.

## 5. Lo que puede salir mal, y qué hacer con ello

**Nunca en el bucle autónomo.** Es la regla más importante del documento. La mitad autónoma corre
cada pocos minutos, todo el día, con la app cerrada. Un modelo de 4 B generando ahí es batería y
calor continuos, y el jugador no lo pediría nunca. El modelo local se enciende **sólo** cuando hay
alguien mirando la pantalla y sólo por una interacción explícita.

**La memoria.** Se comprueba antes de cargar, no al fallar: `ActivityManager.MemoryInfo`,
`isLowRamDevice`, y el propio `memoryClass` del proceso. Y aun así se carga dentro de un intento
que degrada, porque un OOM aquí no es una excepción que se atrapa — el sistema mata el proceso.

**El calor.** Generar sostenidamente estrangula el teléfono y lo nota todo lo demás. El motor se
libera al salir de la pantalla, no se queda caliente «por si acaso».

**Cargar el modelo cuesta segundos.** Cargarlo por mensaje es inusable. El motor vive mientras la
pantalla de hablar está abierta y muere con ella — el mismo patrón que ya usan `CreatureSpeaker` y
`CreatureEars`.

**El primer token tarda.** La criatura no puede congelarse esperando. El estado «pensando» y el
cerebro local cubriendo ya existen y ya funcionan; esto los reusa tal cual.

**Dónde vive el fichero.** No en `cacheDir`: el sistema lo reclama cuando le apetece y el jugador
se encuentra con que la descarga de 3 GB desapareció sola. Va a almacenamiento privado permanente,
se muestra su tamaño en Ajustes, y se puede borrar de una pulsación.

**La descarga.** Ya está resuelta: `UpdateService` tiene descarga con verificación sha256,
comprobación previa de espacio libre, tope de redirecciones y anfitrión fijado. Es el mismo camino,
con otro destino. Lo que hay que añadir es reanudación y descarga sólo por wifi por defecto —
tres gigas por datos móviles es algo que se hace una vez y no se perdona.

## 5 bis. El riesgo que este documento no había visto: la descarga puede no ser desatendida

Es el mayor de todos y no estaba escrito en ninguna parte.

Los pesos de Gemma **no son código abierto**. Van bajo los Gemma Terms of Use, no bajo Apache 2.0
—la librería sí es Apache 2.0, los pesos no—, y la cláusula de redistribución no se ha podido leer
porque la página está bloqueada desde aquí. Así que **el modelo no se sube al repositorio**, ni
ahora ni cuando se pueda leer: se descarga en tiempo de ejecución, que es exactamente lo que hace
la app de Google, y así la pregunta ni se plantea.

Lo incómodo es lo otro. La app de Google **no da por hecho** que la descarga funcione sin
credenciales: prueba la URL sin autenticar, y si no le devuelven 200 arranca un intercambio OAuth
con Hugging Face y reintenta con un *bearer*. Si aun así la rechazan, enseña esto: «This is a gated
model. Please click the button below to view and agree to the user agreement.» Y antes de
cualquier descarga de Gemma, un diálogo de términos.

Si los repositorios que necesitamos están *gated* — **no se ha podido comprobar**, Hugging Face
también está bloqueado desde aquí — entonces bajar el modelo exige que el jugador pase por un
navegador, inicie sesión y acepte unos términos. Eso no es un detalle de implementación: cambia el
rasgo de «púlsalo y espera» a «púlsalo, sal de la app, acepta algo, vuelve». Puede que siga
mereciendo la pena. Pero se decide sabiéndolo, no descubriéndolo con la descarga a medias.

Lo que hay que hacer, en orden: comprobar con un `curl -I` sin autenticar si esas URLs devuelven
200. Si sí, la ruta simple vale y esto se queda en una nota. Si no, hay que diseñar el paso por el
navegador antes de prometer nada en pantalla.

## 6. Lo que esto le cuesta al proyecto

**«Cero recursos» deja de ser literalmente cierto.** Hasta hoy no hay ni un PNG ni un WAV en el
repositorio, y el APK pesa 19 MB. Eso no cambia: el modelo **nunca** entra en el APK. Es un
artefacto que el jugador descarga a propósito, después de instalar, sabiendo lo que pesa. El juego
sigue completo sin él, igual que sigue completo sin el cerebro remoto.

Merece decirse claro porque es la primera vez que este proyecto tiene algo que no cabe en su propio
repositorio.

## 7. Qué se puede verificar aquí y qué no

Como siempre en este proyecto, la lógica de decisión va a `domain/` en Kotlin puro y con tests: qué
modelo tolera este teléfono, cuándo se ofrece la descarga, a quién se enruta cada petición, y qué
pasa cuando el motor no está. Eso son funciones puras y se prueban sin dispositivo, como
`Cadence`, `AppVersion` y `UpdatePlan`.

Lo que **no** se puede verificar aquí, y hay que decirlo por adelantado: no hay SDK de Android en
este entorno, así que el motor no se carga, el modelo no se corre y nadie mide una latencia real.
Todo lo que toque `litertlm-android` queda razonado y comprobado con diffs de diagnósticos de dos
árboles — que es exactamente lo que ya pasa con el widget, la voz y el actualizador, y exactamente
por lo que tres builds se pusieron rojas. La primera medición de verdad sale de un teléfono.

## 8. Lo decidido, y lo que queda abierto

**Decidido:**

1. **Wifi por defecto**, con una forma clara de saltárselo. Tres gigas por datos móviles se hace
   una vez y no se perdona.
2. **Se ofrece tarde, no en la primera partida.** Pedir gigas a alguien que lleva dos minutos con
   un huevo es la forma más rápida de que desinstale. Se ofrece cuando ya hay algo que perder.
3. **Se empaquetan los tres escalones**, y cuál se ofrece es una medición, nunca una pregunta. El
   jugador puede forzar uno más pequeño; no puede forzar uno que su teléfono va a matar.

**Abierto, y sólo un teléfono puede cerrarlo:**

- **¿Se nota siquiera la diferencia?** La criatura habla en frases de una o dos líneas. Puede que
  el de 557 MiB sea indistinguible del de 3,41 GiB para este uso concreto — y si lo es, el grande
  sobra y con él sobran 3 GB, la batería y una gama entera de teléfonos. Es medible. No está
  medido.
- **¿Están *gated* los repositorios?** Ver §5 bis. Es un `curl -I` desde una máquina con salida a
  Hugging Face.
