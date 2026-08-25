# Dónde viven las palabras

Cierre (parcial) de §6.1 del PLAN. Lo mecánico —mover literales a `strings.xml`— está hecho.
Lo interesante era decidir **qué es cromo y qué es contenido**, y sobre todo cómo sacar texto del
dominio sin meterle un `Context`, que es lo único que hace que 778 tests corran sin teléfono.

Este documento existe porque la parte que **no** se movió es una decisión, no un descuido.

---

## 1. La regla

> El dominio dice **qué**. La UI dice **cómo se llama**.

Nada de `domain/` importa nada de Android. Ni ahora ni después de este cambio: se puede
comprobar en una línea.

```
grep -rn "^import android" app/src/main/java/com/neopal/pet/domain/   # sin resultados
```

Cuando el dominio necesita que se diga algo, devuelve **una identidad** —un enum, un tipo— y la
UI la convierte en palabras. Cuando la UI necesita palabras dentro de un `LaunchedEffect`, un
`semantics {}` o un callback —sitios donde `stringResource` es ilegal porque la composición ya
terminó— las **lee en el borde** y pasa el valor hacia dentro. Eso es todo el truco, y aparece
literalmente decenas de veces en este cambio.

---

## 2. Las tres categorías, y qué se hizo con cada una

### 2.1 Cromo de interfaz — **movido, entero**

Títulos, etiquetas de botón, cabeceras de sección, textos de ajustes, estados vacíos, textos de
ayuda, y **todas** las descripciones de accesibilidad. Unas 745 cadenas y 15 `<plurals>`.

Cuatro cosas que sólo se ven una vez que el texto está en un fichero de recursos:

* **Ya existían once recursos `action_*` que nada referenciaba.** El muelle de la pantalla
  principal escribía "Feed", "Clean", "Play"… otra vez en Kotlin, al lado de un `strings.xml` que
  ya los tenía —y de un `values-es/` que ya los tenía traducidos. Nadie lo veía porque nada lo
  comprobaba. Ahora hay algo que lo comprueba (`scratchpad/i18n/argcheck.py`).

* **Siete helpers de plural escritos a mano** (`missionCount`, `dayWord`, `letterWord`,
  `sittingWord`, `thingWord`, `creatureCount`, `childCount`, `eggCount`) eran todos
  `if (n == 1) "x" else "xs"`. En inglés la rama es correcta y lo seguiría siendo hasta la primera
  lengua con tres formas. El español tiene dos, así que tampoco urgía; el punto es que
  `<plurals>` ya existe para esto y el coste era un `@Composable` en un one-liner. La tarjeta de
  ausencia decía literalmente `"day(s)"`, que es el único sitio donde el propio texto se rendía.

* **`", "` y `" and "` son recursos.** Parecen puntuación y son gramática. Unir una lista de
  nombres no se hace igual en todas las lenguas y este es el sitio más barato posible en el que
  haberse equivocado.

* **Las comillas del diario son cromo, la línea de dentro no.** `“%1$s”` es un recurso
  compartido; una lengua que cite con «» cambia el recurso y no toca ni una entrada del diario.

### 2.2 Texto del dominio que en realidad era **identidad** — el caso que sí se arregló

Había un ejemplo perfecto y estaba roto.

`CareActions.topNeed()` devolvía una de cinco palabras inglesas. Dos sitios la consumían:

* `HomeScreen` comparaba contra literales: `when (topNeed(pet)) { "Hungry" -> … }`, con
  `else -> null` al final.
* `WidgetSnapshot` tenía **un segundo enum**, `WidgetNeed`, cuyo único trabajo era reconocer esas
  mismas cinco palabras y volver a convertirlas en casos: `ofLabel(topNeed(now))`.

Es decir: un viaje de ida y vuelta por el inglés para ir de una decisión a una decisión. Y el
propio comentario de `WidgetNeed` admitía el modo de fallo — *"un widget que se ha quedado atrás
respecto a un cambio del juego no dice nada en vez de adivinar"*. Eso es lo que pasa cuando las
dos grafías se separan: nada avisa, el chip simplemente deja de aparecer. Y traducir el dominio
—que es exactamente lo que §6.1 propone algún día— habría roto los dos consumidores en silencio.

**Arreglado.** `topNeed` devuelve `PetNeed`, un enum. `WidgetNeed` y `ofLabel` han desaparecido.
Los dos `when` son ahora exhaustivos sobre el mismo enum. Comprobado por mutación: añadir un
sexto `PetNeed` rompe la compilación en los dos sitios.

```
app/src/main/java/com/neopal/pet/domain/WidgetSnapshot.kt:271: error: 'when' expression must be
    exhaustive. Add the 'LONELY' branch or an 'else' branch.
app/src/main/java/com/neopal/pet/ui/screens/HomeScreen.kt:496: error: 'when' expression must be
    exhaustive. Add the 'LONELY' branch or an 'else' branch.
```

Antes de este cambio, esa misma mutación **no rompía nada**: `HomeScreen` tenía un `else` y
`ofLabel` devolvía `null`. El widget se habría quedado callado y nadie se habría enterado.

`PetNeed` conserva un `label: String` porque el widget imprime esa palabra hoy. Es el último
inglés que queda ahí y se queda a propósito: el widget construye su propia copia dentro de un
fichero puro para poder montar un `RemoteViews` fuera del hilo principal, y mover eso es un
cambio distinto con otra forma. Está anotado en el propio enum.

### 2.3 Texto del dominio que es **contenido** — argumentado, no movido

Y aquí está la parte que hay que defender.

**`Lore`, `Chronicle`, `CreatureVoice`, las líneas del diario: son contenido, no cadenas de
interfaz.** Un recurso de interfaz es una etiqueta: un traductor puede sustituirla sin saber nada
del producto y el resultado sigue siendo correcto. Una entrada del diario no es eso. Es lo que
esta criatura escribió sobre este día, con el registro y la voz que el juego le da; está más
cerca de un nivel de un plataformas que de un botón "Guardar". Meter 674 líneas de `Lore.kt` en
`strings.xml` no las hace localizables — las hace *un `strings.xml` de 674 entradas que nadie
puede traducir sin jugar la partida entera*, y a cambio pierde la propiedad que las hace
tratables hoy: que un test puro puede leerlas.

El criterio con el que se decidió, y que se aplicó también hacia el otro lado:

> ¿Podría alguien que no ha jugado nunca sustituir esta cadena y acertar?
> Si sí, es cromo. Si no, es contenido.

Aplicándolo honestamente, **la burbuja de estado de ánimo sobre la criatura sí se movió**, y es
la decisión más discutible de las dos direcciones. Parece la criatura hablando ("I'm hungry!",
"Play with me?"), pero nada en `domain/` la escribe: la UI la deriva de cinco umbrales de stats.
Es un piloto de estado disfrazado de bocadillo. Se mueve. El diario, que la criatura sí escribe,
no.

**Lo que sí debería moverse algún día, y por qué no es "meter `Context`":** los mensajes de
`CareActions` ("Pip is completely full.", "Bounce Ball is not food.") **sí son identidad**, no
contenido — son un catálogo cerrado de negativas, no prosa. La forma correcta es la misma que se
acaba de demostrar con `PetNeed`: `ActionResult.toast: String?` pasa a ser
`ActionResult.message: CareMessage?`, una jerarquía sellada con los datos que la frase necesita
(`CareMessage.TooFull(name)`, `CareMessage.NotFood(itemName)`), y `PetViewModel` —que ya tiene
`Application`— la convierte en palabras. Sólo hay **un** consumidor
(`PetViewModel.kt:866 → HomeScreen`), así que el cambio está acotado. No se hizo en esta pasada
porque son ~50 sitios en `CareActions` y esta pasada ya toca 30 ficheros; mezclarlo haría el
diff irrevisable. **Ese es el siguiente paso, y no necesita ni un import de Android en el
dominio.**

Lo mismo, con la misma forma, para: `Nudges` (títulos y cuerpos de notificación),
`Errands`, `Missions.title/description`, `ItemCatalog.name/description`, `Skill.description`,
`Brain.blockedBy` y `Consideration.reason`.

`Brain.blockedBy` merece una nota aparte porque es el **mismo bug que `topNeed` tenía**, todavía
vivo: `HomeScreen.kt:257` hace `it.blockedBy == "not learned yet"`. Una cadena inglesa del
dominio comparada por igualdad desde la UI. Se deja marcada aquí, sin tocar, porque arreglarla
bien es el refactor de `CareMessage` de arriba y no un parche.

---

## 3. La misma pregunta, un directorio más allá

`ui/games/` no tiene el problema de pureza que tiene `domain/` —es UI— pero tiene la misma forma:

* `HideProp.label` ("the crate"), `PuzzleShape.label` ("circle"), `Judgement.label` ("PERFECT"):
  enums que llevan su propio inglés.
* `hideHintFor`, `hideInstinctSentence`, `describeAnswer`, `FetchSim.phaseSentence`: funciones no
  composables que **construyen prosa** a partir de esos enums, dentro del bucle de simulación.

No se movieron. La razón no es pereza: extraer la frase dejando el sustantivo clavado dentro le
entrega al traductor un marco con inglés soldado. La cabecera, los resultados y los botones de
esos juegos sí se movieron; el comentario en directo no.

Y hay una prueba de que las palabras son el contrato equivocado, escrita por el propio proyecto:

```kotlin
// HideAndSeekInstinctsTest.kt
if (hint.contains("to the right")) {
    assertTrue("$where, but it is to the left", b.x > a.x)
}
```

Un test que **lee prosa inglesa para comprobar un rumbo**. Funciona, y deja de funcionar el día
que alguien reescribe la frase por estilo. Si `hideHintFor` devolviera
`Hint(across: Side?, along: Depth?, close: Boolean)`, el test afirmaría sobre el rumbo y la UI se
encargaría de las palabras — y el test sería más fuerte, no más débil. Ese es el argumento
entero de §6.1 en cinco líneas.

---

## 4. Lo que **no** debe traducirse nunca

`PetViewModel.unpromptedLine()` construye frases como *"You have just grown into a Teen. Say
something about it."* Eso **no** es interfaz: es el **prompt** que se le manda a un modelo de
lenguaje. Traducirlo cambia lo que se le pregunta a la criatura y por tanto lo que responde. Es
una decisión de producto sobre el modelo y no tiene nada que ver con el idioma de los botones.
Anotado en el propio código para que nadie lo "arregle".

Tampoco se movieron, y por razones parecidas: claves de sprite (`"cake"`, `"pill"`), rutas de
navegación, etiquetas de animación (`label = "boot-clock"`), claves del formato de guardado, e
identificadores de ítem. Ninguno es texto.

La marca **NEOPAL** de la pantalla de arranque tampoco. Es la marca, no copia. El eslogan de
debajo sí.

---

## 5. Traducir es ahora un fichero, no un cambio de código

Ya existe `app/src/main/res/values-es/strings.xml` con 54 entradas. Con esta pasada dentro, el
resto de la interfaz —745 cadenas y 15 plurales— es **rellenar ese fichero**. Cero Kotlin. Cero
riesgo de romper un test. Android hace fallback por cadena, así que un `values-es/` a medias no
rompe nada: lo que falte sale en inglés.

Deliberadamente **no se tradujo nada en esta pasada**. Extracción y traducción son dos cambios y
mezclarlos hace los dos irrevisables: en un diff mezclado no se puede ver si una cadena cambió de
sitio o de significado.

---

## 6. Lo que comprueba que esto no se rompa

Aquí no hay SDK de Android ni Google Maven, así que nada de esto compila del todo en local. Las
herramientas están en `scratchpad/i18n/`:

| Qué | Qué atrapa |
|---|---|
| `argcheck.py` | Un `R.string.x` que no existe; un sitio que pasa 2 argumentos a un recurso con 3 `%N$`; un apóstrofo sin escapar; un `%` suelto; un recurso que nadie referencia. Nada de esto lo ve el compilador: `stringResource` es vararg y `R` se genera. |
| `xmlcheck.sh` | XML roto. Un `don't` sin escapar en `strings.xml` tira el build antes de compilar una línea de Kotlin. |
| `genrstub.py` | Genera un `R` de mentira **a partir del `strings.xml` real**, para que `gametest.sh` compile de verdad los ficheros de juego. Atrapó dos `val title` declarados por debajo del efecto que los usaba. |
| `twotree.sh` | Compila el árbol de HEAD y el árbol con mis cambios con el mismo classpath sin Android y compara los diagnósticos. Todo mensaje nuevo tiene que ser un símbolo `android`/`androidx`/`R` sin resolver, o consecuencia directa de uno. |
