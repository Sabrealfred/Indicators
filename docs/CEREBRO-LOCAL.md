# Un cerebro dentro del teléfono

Diseño, no implementación. Nada de esto está construido todavía.

La petición: que la criatura tenga un modelo corriendo **en el propio teléfono**, y que cuando
haya internet pueda usar el grande. El presupuesto declarado es de hasta 5 GB de app.

---

## 1. La restricción que de verdad aprieta no es el disco

Cinco gigas de disco es holgado. **La RAM es lo que decide**, y es un número mucho más pequeño y
mucho menos negociable:

| | Parámetros efectivos | Disco (int4 QAT) | RAM en ejecución | Teléfono mínimo |
|---|---|---|---|---|
| Gemma 4 **E2B** | ~2,3 B | ~1,3 GB | 2–3 GB | prácticamente cualquiera desde 2022 |
| Gemma 4 **E4B** | ~4,5 B | ~3 GB | ~5 GB | gama alta reciente, 8 GB de RAM |

Android no te da la RAM del teléfono: te da un presupuesto por proceso, y lo hace cumplir matando
la app. Un modelo que pide 5 GB en un teléfono de 6 GB no va lento — **desaparece**, y para el
jugador eso es indistinguible de que la mascota se haya muerto. Es el peor fallo posible en este
juego concreto.

Así que «el mejor que haya» no puede significar un solo modelo. Significa **el mejor que este
teléfono pueda sostener de verdad**.

## 2. Qué modelo

**Gemma 4, cuantizado int4 con QAT, en formato `.litertlm`.** Razones, en orden de peso:

1. **QAT, no cuantización posterior.** Gemma 4 se entrena *sabiendo* que va a correr en 4 bits, así
   que el int4 conserva calidad cercana a bfloat16 en vez de degradarse. Es la diferencia entre un
   modelo pequeño y un modelo grande estropeado.
2. **Multilingüe de serie**: 35+ idiomas soportados, 140+ preentrenados. El español importa aquí y
   no es un añadido.
3. **Es el camino que Google mantiene.** MediaPipe `tasks-genai` está en modo mantenimiento y su
   propia guía dice que migres. LiteRT-LM es la ruta viva, con delegados de CPU/GPU/NPU y
   *embeddings* por capa mapeados en memoria, que es justo el truco que deja a E2B por debajo de
   1,5 GB en algunos dispositivos.

```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:<versión>")
```

**Se empaquetan los dos.** No como una pregunta al jugador —«¿ligero o bueno?» no es una pregunta
que nadie pueda responder— sino como una medición: se lee la memoria disponible del proceso y se
ofrece E4B sólo donde cabe, E2B donde no, y nada donde tampoco. El jugador puede forzar el
pequeño si prefiere gastar menos batería; no puede forzar el grande en un teléfono que lo va a
matar.

> **Por verificar antes de escribir código:** el tamaño exacto del `.litertlm` de E4B, la versión
> concreta de `litertlm-android` en Google Maven, y si E4B está publicado en ese formato o sólo en
> GGUF. Lo he leído en resúmenes, no en el artefacto. Nada de esto se decide de memoria.

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

## 8. Lo que hace falta decidir

1. **¿Descarga sólo por wifi, o se le deja elegir?** Yo pondría wifi por defecto y un aviso claro
   para saltárselo.
2. **¿Se ofrece el modelo local en la primera partida, o cuando el jugador ya se ha encariñado?**
   Pedir tres gigas a alguien que lleva dos minutos con un huevo es la forma más rápida de que
   desinstale.
3. **¿E4B siquiera merece la pena para esto?** La criatura habla en frases de una o dos líneas.
   Puede que E2B sea indistinguible en la práctica y ahorre 2 GB, batería y media gama entera de
   teléfonos. Es medible, y habría que medirlo antes de dar por buena la respuesta «el mejor».
