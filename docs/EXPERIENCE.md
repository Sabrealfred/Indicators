# NeoPal — Documento de experiencia

> Este documento es la columna vertebral de diseño. `FEATURES.md` dice **qué hay**; esto dice
> **por qué está** y **qué debe sentir el jugador**. Cuando una decisión de código, arte o texto
> entre en conflicto con este documento, se discute aquí primero.
>
> Todos los números citados están verificados contra `domain/Simulation.kt`, `domain/Pet.kt`,
> `domain/CareActions.kt`, `domain/Items.kt`, `domain/Achievements.kt` y `domain/Chronicle.kt`
> al día de escritura. Si el código cambia, este documento se corrige, no al revés.

---

## 1. La tesis

Una mascota virtual no es un simulador de crianza. Es un aparato para fabricar **culpa
proporcional al cariño**, y ese es todo el truco.

El género se inventó con una premisa incómoda: te entrego una criatura que depende de que
mires una pantalla, y que se degrada exactamente igual de rápido estés mirando o no. No hay
pausa. No hay "guardar y salir". El tiempo que pasás sin pensar en ella es tiempo que ella
pasa esperándote. Esa es la única mecánica que importa; todo lo demás —las estadísticas, las
monedas, los minijuegos, los sombreros— es andamiaje para que esa asimetría se pueda medir.

**NeoPal es un juego sobre la atención, no sobre la optimización.** La diferencia es
concreta y tiene consecuencias de diseño:

- Un juego de optimización premia *la mejor jugada*. Un juego de atención premia *la jugada a
  tiempo*. Darle de comer cuando la saciedad está en 12 y darle de comer cuando está en 45 son
  numéricamente casi lo mismo; emocionalmente son cosas distintas, y NeoPal debe notar la
  diferencia.
- Un juego de optimización tiene una partida óptima. NeoPal no debe tenerla. Las cinco ramas
  evolutivas (`Balanced`, `Athletic`, `Gourmand`, `Scholar`, `Feral`) **no están ordenadas de
  peor a mejor**: son cinco retratos de cinco maneras de estar presente. `Feral` no es el
  fracaso; es la criatura que aprendió a arreglárselas sola. Si el juego alguna vez sugiere lo
  contrario —y hoy lo sugiere, ver §8— está trabajando en contra de sí mismo.
- Un juego de optimización se termina. NeoPal termina siempre, y siempre igual: la criatura se
  muere. `DeathReason.OLD_AGE` no es una condición de derrota, es el final bueno. La partida
  perfecta también acaba en una lápida.

La mortalidad no es un castigo colgado al final del tubo: es lo que le da precio al minuto de
hoy. Sabemos, desde el segundo uno, que esto dura unas **cinco horas de reloj real** (18.090
segundos, §2). Nada de lo que hagas lo alarga. Lo único que podés decidir es **cómo fueron esas
cinco horas** — y eso es exactamente lo que el álbum y el cuaderno existen para guardar.

Corolario, y es la regla que más veces vamos a tener que defender: **NeoPal nunca debe usar la
culpa como palanca de retención.** La culpa es el material del que está hecho el juego, no la
herramienta con la que se lo vende. Una notificación que dice "tu mascota murió, abrí la app
para empezar de nuevo" convierte un duelo en un embudo. Una pantalla que te muestra el contador
de "errores de cuidado" en el memorial convierte un recuerdo en una boleta. El juego puede —
debe — dejar que la criatura diga que la pasó mal. No puede decirte que sos mala persona, y no
puede insinuar que si volvés más seguido eso se arregla.

Lo que queremos que quede después de desinstalar: no un puntaje. Una frase del cuaderno.

---

## 2. El arco de una vida

### 2.1 Los tiempos reales

`GameConfig.secondsPerPetDay` vale `1_200` por defecto: **un día-mascota = 20 minutos reales**.
El ajuste "Pace" lo mueve entre 2 y 60 minutos, así que todo lo de abajo escala salvo el huevo.

`Simulation.stageDuration()` define, en días-mascota:

| Etapa | Duración (días-mascota) | Real (por defecto) | Edad al terminar | Multiplicador de desgaste |
|---|---|---|---|---|
| Huevo | `EGG_HATCH_SECONDS = 90 s` **fijo** | 1 min 30 s | 1,5 min | 0 (nada decae) |
| Bebé | 1 | 20 min | 21,5 min | ×1,30 |
| Niño | 2 | 40 min | 1 h 01 min | ×1,10 |
| Adolescente | 3 | 60 min | 2 h 01 min | ×1,00 |
| Adulto | 5 | 100 min | 3 h 41 min | ×0,90 |
| Anciano | 4 | 80 min | 5 h 01 min | ×1,05 |

**Vida completa por vejez: 18.090 segundos ≈ 5 h 01 min de reloj real ≈ 15 días-mascota.**
No son cinco horas *de juego*: son cinco horas *de existir*, y el mundo avanza igual con la app
cerrada (con el tope de `maxOfflineSeconds = 12 h`).

Ritmos derivados que hay que tener en la cabeza al diseñar cualquier pantalla:

| Necesidad | Fórmula (por segundo) | Ventana práctica |
|---|---|---|
| Saciedad | `0,085 × etapa × hungerBias × (1,2 si Greedy)` | de 100 a 0 en **13 a 26 min** según especie y etapa |
| Energía | `0,050 × etapa × energyBias` | se duerme sola (energía ≤ 8) cada **15–25 min** |
| Sueño | recupera `0,320` hasta 98 | siesta de **≈ 4,7 min** |
| Felicidad | `0,045 × etapa × personalidad` | entra en enfurruñe (< 12) en **15 a 35 min** |
| Higiene | `0,028 × etapa + 0,02 por cada popó` | "sucio" (< 30) en **≈ 45 min** limpio, mucho antes con popó |
| Disciplina | `0,004` sólo despierta | cae de 20 a 15 en **≈ 21 min despierta** |
| Vínculo | `0,005` | −18 puntos por hora sin intervenir |
| Popó | prob. `0,0009 × (0,5 + saciedad/100)` por segundo | uno cada **≈ 14 min** bien alimentada |
| Enfermedad | chequeo cada 30 s, riesgo base 0,4 % | ≈ 38 % por hora en condiciones limpias; sube a >15 % *por chequeo* con sala sucia + hambre |
| Muerte por abandono total | saciedad a 0, luego salud a `−0,055/s` | **43 a 56 min** desde estadísticas llenas |

Ese último número es el más importante del juego y hoy está roto (§8.1).

### 2.2 Huevo — 90 segundos

- **Qué siente el jugador:** impaciencia con permiso. Es el único momento en que no hacer nada
  es lo correcto, y hay que venderlo como tal.
- **Qué necesita la mascota:** nada. `stageMult = 0`: ninguna estadística se mueve.
- **Qué debe revelar el juego:** que el tiempo corre solo. El cascarón se agrieta por etapas
  aunque cierres la app; volver y encontrarlo más agrietado enseña la regla central sin una
  línea de tutorial.
- **La tensión:** ninguna, y está bien. Es el respiro antes del contrato.
- **La mecánica que lo sostiene:** el temporizador y la animación de grieta progresiva. Hoy
  `CareActions.guard()` bloquea absolutamente todo con "The egg is still warming up...", así que
  el primer minuto y medio de NeoPal es una pantalla que dice que no a todo. Eso hay que
  arreglarlo (§9, R5): el jugador debe poder tocar, abrigar o hablarle al huevo, aunque no
  cambie nada mecánicamente. Un gesto sin efecto sigue siendo un gesto.
- **Nota:** los 90 s son constantes, **no** escalan con "Pace". Un jugador que ponga el día en
  2 minutos igual espera 90 segundos. Es defendible (el nacimiento no se acelera) pero hay que
  decidirlo a propósito, no por descuido.

### 2.3 Bebé — 20 minutos

- **Qué siente el jugador:** susto útil. La cría gasta un 30 % más rápido que nadie
  (`stageMult = 1,30`); un Ember glotón vacía la saciedad en **12 min 40 s**. Es la etapa donde
  se aprende que esto pide algo.
- **Qué necesita la mascota:** comida y presencia física. Al eclosionar arranca con
  `happiness = 80`, `satiety = 60` — no llena: **con hambre desde el primer segundo**. Es
  deliberado y es correcto.
- **Qué debe revelar el juego:** los cuatro botones que importan (comer, limpiar, luz, mimo) y
  el hecho de que la criatura **reacciona a que la toques** (`pet`, `tickle`, `toss`) sin que
  eso sea "productivo".
- **La tensión:** el jugador todavía no sabe cuánto tiempo tiene. Todo se siente urgente.
- **La mecánica que lo sostiene:** frecuencia. En 20 minutos hay ~2 comidas, ~1,5 popós y ~1
  siesta. Es un tutorial hecho de eventos reales, no de carteles.

### 2.4 Niño — 40 minutos

- **Qué siente el jugador:** competencia. Ya sabe el ritmo, empieza a anticiparse.
- **Qué necesita la mascota:** variedad. Es cuando los minijuegos y la tienda empiezan a tener
  sentido: hay energía de sobra y monedas que gastar.
- **Qué debe revelar el juego:** que **cómo** cuidás importa, no sólo **si** cuidás. Es la
  ventana donde `careMistakes`, `praises`, `gamesPlayed`, `weightGrams` y `mealsEaten` se están
  acumulando calladamente hacia el veredicto del minuto 61.
- **La tensión:** invisible y por eso peligrosa. El jugador está tomando decisiones que le van a
  cambiar la criatura y no lo sabe. Está bien que no lo sepa **con números**; está mal que no lo
  intuya. La solución no es una barra de progreso hacia "Scholar": es que la criatura empiece a
  tener manías observables (se pone a saltar sola si jugaste mucho; se queda mirando la despensa
  si la sobrealimentaste).
- **La mecánica que lo sostiene:** el peso corporal. Es el único stat que ya se ve en la
  silueta. Debería haber tres o cuatro más de esos.

### 2.5 Adolescente — 60 minutos

- **Qué siente el jugador:** el primer orgullo, y el primer arrepentimiento. A los **61,5
  minutos** se ejecuta `decideBranch()` por primera vez y la criatura sale de la pantalla blanca
  siendo alguien.
- **Qué necesita la mascota:** menos comida (`stageMult = 1,00`) y más interacción. La
  felicidad y el vínculo pesan más que la saciedad.
- **Qué debe revelar el juego:** el veredicto. Es el clímax medio de la partida y hoy se
  entrega como un flash blanco y un logro. Merece un texto: **una línea del cuaderno escrita en
  primera persona** que explique la forma en términos de lo que pasó, no de umbrales
  ("Aprendí a esperar sola. No fue por maldad tuya, pero lo aprendí").
- **La tensión:** la rama no es definitiva. `handleEvolution()` vuelve a llamar a
  `decideBranch()` en el salto a Adulto (minuto **121,5**). Hay una segunda oportunidad y el
  juego jamás la menciona. Debería: "todavía puedo cambiar" es la frase más motivadora que
  NeoPal puede decir sin mentir.
- **La mecánica que lo sostiene:** `decideBranch()` es 100 % determinista. Dos jugadores que
  crían igual obtienen la misma criatura. Eso es un valor y hay que protegerlo.

### 2.6 Adulto — 100 minutos

- **Qué siente el jugador:** calma, y después aburrimiento si no hacemos nada. Es la etapa más
  larga (un tercio de la vida) y la de menor desgaste (`×0,90`).
- **Qué necesita la mascota:** compañía sin urgencia. Es la primera vez que se la puede dejar
  sola 25 minutos sin drama.
- **Qué debe revelar el juego:** la rutina como forma de cariño. Aquí es donde el juego deja de
  pedir y empieza a **acompañar**: es el territorio natural de las estaciones, la música de
  cuarto, las fotos, el cuaderno, los sombreros.
- **La tensión:** el riesgo real es el olvido. Un adulto sano no te llama, y un jugador que no
  es llamado se va. La respuesta correcta **no** es subir el desgaste ni inventar urgencia: es
  darle a la criatura cosas que ofrecer (un dibujo, un recuerdo, una manía nueva). Que valga la
  pena volver aunque no haga falta.
- **La mecánica que lo sostiene:** hoy, casi ninguna. Es el hueco más grande del juego y la
  razón por la que §9 tiene tantos ítems de "presencia".

### 2.7 Anciano — 80 minutos

- **Qué siente el jugador:** ternura y anticipación del duelo. Sabe lo que viene.
- **Qué necesita la mascota:** cuidados otra vez, pero por otro motivo. El desgaste sube a
  `×1,05` y `handleSickness()` le suma un **+2 % fijo de riesgo por chequeo** por ser Elder.
  Se enferma más y se recupera peor.
- **Qué debe revelar el juego:** que el cuidado ya no construye nada. No hay evolución después.
  Alimentarla en la vejez es puro cuidado sin retorno, y ese es el gesto más limpio del juego.
  Hay que dejar que se sienta así: **menos recompensas, menos monedas, menos XP en esta etapa**,
  no más.
- **La tensión:** cada enfermedad ahora puede ser la última, y el reloj de 80 minutos corre
  aunque hagas todo perfecto.
- **La mecánica que lo sostiene:** `handleDeath()` mata por `OLD_AGE` en cuanto
  `secondsInStage >= stageDuration(ELDER)`. Es inevitable por diseño. El jugador debería poder
  **ver** cuánto queda —un reloj honesto en la pantalla de estadísticas, no una barra de
  progreso hacia la muerte— para poder despedirse a tiempo.

### 2.8 Muerte

Cuatro salidas: `STARVATION`, `ILLNESS`, `NEGLECT`, `OLD_AGE`. Las tres primeras son fallos del
jugador; la cuarta es el final del cuento. **El juego debe tratarlas distinto y hoy no lo hace**
(§8.4): el memorial usa la misma lápida, la misma tipografía y el mismo botón para "se murió de
vieja a los 15 días" y para "la dejaste morir de hambre a los 40 minutos".

Lo que debe pasar en los dos casos: el jugador ve **qué vida fue**, no **qué puntaje sacó**.

---

## 3. El arco entre generaciones

### 3.1 Qué se hereda hoy (verificado en `Simulation.nextGeneration`)

| Se hereda | No se hereda |
|---|---|
| `coins` | `level` y `xp` (vuelven a 1 / 0) |
| `album` completo | `highScores` de los minijuegos |
| `chronicle` completo | `personality` (se vuelve a sortear) |
| `unlockedAchievements` | `weightGrams`, todos los contadores de cuidado |
| Inventario **cosmético** (sombreros, cuartos) | Consumibles (se reponen 3 bayas, 2 platos, 1 pastilla) |
| `generation + 1` | El nombre |

### 3.2 Por qué la segunda generación debe sentirse distinta

La primera generación es sobre **aprender el reloj**. La segunda tiene que ser sobre
**saber el reloj**, y esa diferencia de conocimiento es el material dramático:

1. **Ya no hay excusa.** En la gen 1 el jugador no sabía que un adolescente aguanta 25 minutos
   solo. En la gen 2 lo sabe. Las mismas acciones significan otra cosa cuando son informadas.
2. **La casa ya no está vacía.** Hereda monedas, sombreros y cuartos: la nueva criatura nace en
   el mundo que construyó la anterior. Eso hoy es sólo economía; debería ser escenografía —
   el sombrero de la mascota muerta debería estar colgado en el cuarto, no sólo en el inventario.
3. **El álbum ya tiene páginas.** Es la primera vez que el jugador ve dos criaturas en la misma
   pantalla, y ahí nace el sentido de linaje.
4. **La progresión se reinicia y eso es correcto.** Perder el nivel de cuidador dice: la
   experiencia no se transfiere, el vínculo sí. Es exactamente la tesis. Lo que **no** es
   correcto es perder los récords de los minijuegos sin decírselo a nadie (§8.6).

### 3.3 El juego largo (generación 3+)

A partir de la tercera, la unidad narrativa deja de ser la vida y pasa a ser **la casa**. El
jugador ya no pregunta "¿cómo hago para que llegue a anciano?" sino "¿qué clase de cuidador
soy?". El juego debe poder contestarlo:

- **El linaje visible.** Un árbol de generaciones: nombre, especie, rama, días vividos, causa
  de muerte, una foto. Sin puntajes ni comparaciones. Es un cementerio amable y es el verdadero
  metajuego.
- **Repetición con variación.** Cuatro especies × cinco ramas × cinco personalidades = 100
  retratos posibles. El coleccionismo aquí es legítimo *si* el juego no lo convierte en una
  checklist con casillas vacías gritando.
- **El eco.** La generación N debería poder heredar **una** cosa concreta e irracional de la
  N−1: un juguete favorito, una manía, una frase que aparece en el cuaderno. Nada que altere
  estadísticas. Sólo memoria.
- **El final honesto.** El juego no tiene un final y no debe fingir uno. Lo más cerca de un
  cierre es la carta que escribe un anciano antes de morir (§9, R12). Después de eso, seguir es
  elección, no obligación.

---

## 4. Los latidos emocionales

Ocho momentos. Si estos ocho funcionan, el juego funciona, aunque el resto sea mediocre. Si uno
falla, no hay contenido que lo compense.

### 4.1 La primera eclosión (minuto 1:30)

- **Qué debe sentir:** que algo cruzó desde el otro lado del vidrio.
- **Qué debe hacer la app:** el cascarón se agrieta en tres pasos visibles antes del final —
  el jugador debe *ver venir* el momento y quedarse mirando. Explosión de partículas, sonido de
  eclosión, y después **dos segundos de silencio absoluto** con la criatura sola en pantalla,
  sin HUD, sin barras, sin toast. La primera línea del cuaderno ya existe y es la correcta:
  *"Abrí los ojos. Lo primero que vi fuiste vos."* Las barras aparecen recién después.
- **Error a evitar:** mostrar el logro "It moved!" encima de la eclosión. El banner tapa el
  momento. Que espere cinco segundos.

### 4.2 El primer rechazo de comida

- **Cuándo:** `CareActions.feed()` bloquea si `isSulking` (felicidad < 12) o si la saciedad
  llegó a 96. Son dos rechazos distintos y hoy suenan casi igual.
- **Qué debe sentir:** desconcierto, y después entendimiento. "No come porque está *triste*"
  es la primera vez que el jugador piensa en la criatura como sujeto y no como barra.
- **Qué debe hacer la app:** el rechazo por enfurruñe necesita animación propia — se da vuelta,
  no una negación genérica — y una línea que **no explique la mecánica**: "se da vuelta" es
  mejor que "necesita más felicidad para comer". El jugador tiene que deducirlo. Y la solución
  (mimar, jugar, y recién después dar de comer) tiene que funcionar en menos de un minuto, o
  el descubrimiento se vuelve frustración.
- **Diferenciar:** "está llena" es una broma. "No quiere comer" es un aviso. Tonos opuestos.

### 4.3 La primera enfermedad

- **Cuándo:** `handleSickness()` chequea cada 30 s; con la sala limpia el riesgo base es 0,4 %
  por chequeo (≈ 38 % por hora), así que casi todas las partidas la ven una vez.
- **Qué debe sentir:** alarma proporcionada. No pánico: la enfermedad no mata rápido
  (`−0,055 × 0,6 = 0,033/s` de salud, ≈ 50 min desde salud llena).
- **Qué debe hacer la app:** cambiar el ambiente, no poner un icono. El aura verde pulsante ya
  existe; hay que sumarle que la criatura **deje de hacer cosas** — sin idle animado, sin
  reaccionar a los mimos con la misma alegría. Y que el cuaderno escriba, en ese instante,
  *"No me siento bien. Ojalá lo notes pronto."* — que ya existe, y es exactamente el registro
  correcto: dice su experiencia, no tu obligación.
- **La curación tiene que ser un evento.** `medicine` cura con `happiness = −6`: se toma la
  pastilla con cara de asco. Eso es oro; hay que animarlo. `medicine_super` cura con `+6`. Dos
  animaciones distintas.

### 4.4 La primera evolución revelada (minuto 61,5)

- **Qué debe sentir:** que el resultado es suyo. No sorpresa aleatoria: **reconocimiento**.
- **Qué debe hacer la app:** el estirón + fogonazo + forma nueva ya está. Falta lo que lo
  convierte en un latido: después del flash, una **tarjeta de retrato** con la forma nueva, su
  nombre, y una sola frase que ligue la forma a la crianza. Se guarda sola en el álbum
  (`handleEvolution` ya lo hace) y la app debe *mostrar que se guardó* — la miniatura vuela
  hacia el icono del álbum. Ahí es donde el jugador aprende que el álbum existe.
- **Error grave que hay hoy:** la pantalla de estadísticas muestra `Next form` con
  `Simulation.decideBranch(pet)` en vivo, desde el minuto 2. El clímax está spoileado por un
  campo de una tabla (§8.3).

### 4.5 Dejar la luz encendida

- **La mecánica:** si duerme con `lightsOff = false`, pierde `0,045 × 1,5 = 0,0675` de
  felicidad por segundo (≈ 4 puntos por minuto) y `handleCareMistakes()` registra un error de
  cuidado **cada 60 segundos**.
- **Qué debe sentir:** una punzada pequeña y específica. Es el mejor gesto del juego porque
  cuesta cero esfuerzo y cero recursos: sólo hay que **acordarse**. Es la tesis en un botón.
- **Qué debe hacer la app:** hacerlo legible sin regañar. Cuando se duerme con la luz prendida,
  la criatura se tapa la cara, se da vuelta, se mueve incómoda. Nada de texto rojo. Nada de
  "¡Estás lastimando a tu mascota!". La imagen alcanza.
- **Ajuste necesario:** hoy una siesta por agotamiento a mediodía con la luz encendida acumula
  ~5 errores de cuidado y empuja a `FERAL` sin que el jugador se entere de nada (§8.2). El
  castigo tiene que ser proporcional y visible o no es un latido, es una trampa.

### 4.6 Volver después de estar afuera

- **Qué debe sentir:** alivio, o el peso de lo que pasó. Nunca una auditoría.
- **Qué debe hacer la app:** al volver, `onResumed()` corre la simulación de golpe. Hoy eso
  produce una avalancha de eventos y toasts. En lugar de eso: **una tarjeta de reencuentro**.
  "Estuviste fuera 3 h 20 min. Mientras tanto: comió lo que quedaba, durmió dos veces, se
  ensució." Hechos, en pasado, sin adjetivos. Y si la pasó mal, se dice sin acusar: "estuvo con
  hambre unas dos horas."
- **Y lo que la criatura hace al verte:** si el vínculo es alto, corre hacia el vidrio. Si el
  vínculo cayó, tarda en acercarse. Eso es toda la información que el jugador necesita.
- **Nunca:** una cuenta de todo lo que hiciste mal. Nunca un "¡te extrañó!" con signos de
  exclamación cuando en realidad estuvo sola.

### 4.7 La pantalla de muerte

- **Qué debe sentir:** duelo real, en escala de juguete. No hay que exagerarlo ni escaparle.
- **Qué debe hacer la app:**
  - **Silencio primero.** Sin música, sin toast, sin logro. La criatura se apaga y la pantalla
    se queda quieta unos segundos antes de que aparezca nada.
  - **Dos memoriales distintos.** Vejez: cálido, dorado, "vivió 15 días". Hambre / negligencia /
    enfermedad: frío, corto, honesto, sin sermón. La línea del cuaderno ya está escrita y es
    dura y correcta (*"Esperé una comida que no llegó"*). Eso es lo máximo que el juego puede
    decir; una palabra más y es manipulación.
  - **El botón de continuar no puede ser el primer botón.** Hoy "Raise generation N+1" es
    primario y "Stay a moment" es secundario. Hay que invertirlos. Quedarse debe ser lo fácil.
  - **Nada de estadísticas de rendimiento.** El memorial actual imprime "N care mistakes". Eso
    es una boleta al pie de una lápida. Fuera.
- **La notificación de muerte tiene que desaparecer.** "Tu mascota murió, abrí la app para
  empezar una nueva generación" es un anzuelo de reenganche disfrazado de aviso (§8.5).

### 4.8 La primera foto del álbum

- **Qué debe sentir:** autoría. "Yo elegí guardar este momento."
- **Qué debe hacer la app:** el gesto ya es el correcto — mantener pulsada la criatura. Hay que
  vestirlo: cierre de obturador, congelamiento de un frame, el marco de la postal apareciendo
  alrededor. Y **dejar poner un título propio** (`snapshot()` ya lo acepta) con un texto
  sugerido, no impuesto.
- **La regla de oro:** el álbum no se ordena por calidad, no tiene rareza, no tiene estrellas y
  no se puede "completar". Es un álbum, no una colección.
- **Bug de experiencia a arreglar ya:** `snapshot()` hace `(state.album + entry).takeLast(60)`.
  Sacar la foto número 61 **borra la primera** — que muy probablemente sea la eclosión. El
  juego destruye silenciosamente el recuerdo más antiguo justo cuando el jugador está haciendo
  el gesto de recordar. Es el peor bug posible en este juego (§8.7).

---

## 5. El bucle de sesión

### 5.1 Los dos modos

NeoPal tiene exactamente dos sesiones y hay que diseñar las dos, no una:

| | **El vistazo (30–60 s)** | **La visita (5–15 min)** |
|---|---|---|
| Frecuencia | 4–8 veces al día | 1–2 veces al día |
| Disparador | notificación, o costumbre | tiempo libre, aburrimiento |
| Qué hace el jugador | mira, corrige lo urgente, sale | juega, compra, decora, saca fotos, lee el cuaderno |
| Qué necesita | leer el estado en **menos de dos segundos** | tener algo que hacer que no sea mantenimiento |
| Éxito | salir sin culpa | salir con algo nuevo (una foto, una línea, una forma) |
| Fracaso de diseño | tener que abrir tres pantallas para dar de comer | que todo el contenido sea mantenimiento con otro nombre |

**Lo que debe ser cierto en los dos:**

1. **El estado se lee de la pantalla, no de las barras.** Si el jugador tiene que mirar números
   para saber cómo está, el arte falló. Postura, cara, sala, luz: eso primero.
2. **Ninguna acción esencial está a más de un toque de la pantalla principal.** Comer, limpiar,
   luz y mimo viven en el dock; nada más es urgente.
3. **Salir nunca es un error.** El juego no tiene "cerrá bien la app". No hay una acción de
   despedida obligatoria. Apagar la luz es una gentileza, no un checklist.
4. **El juego nunca inventa urgencia.** Si no pasa nada, no dice nada. `Notifications.careMessage()`
   ya devuelve `null` cuando la mascota está bien y eso es una decisión de diseño excelente que
   hay que defender contra cualquier intento de "engagement".
5. **Cada visita deja rastro.** Una línea de cuaderno, una foto, una moneda. Que abrir la app
   nunca sea neutro.

### 5.2 Un día en la vida (jugador real, ritmo por defecto)

| Hora | Qué pasa | Sesión | Estado de la criatura |
|---|---|---|---|
| 07:40 | Suena el despertador, abre NeoPal en la cama. Tarjeta de reencuentro: durmió, se ensució una vez. Le da un plato, limpia, prende la luz. | 50 s | Bebé, día-mascota 1 |
| 08:15 | En el colectivo. Vistazo. Está bien. Le hace cosquillas y saca una foto. | 40 s | — |
| 09:30 | Notificación: tiene hambre. Come. | 25 s | Pasó a Niño ~09:00 |
| 12:30 | Almuerzo. Sesión larga: dos partidas de Snack Catch, compra el Gorro de Hoja, prueba el cuarto de playa. | 11 min | Niño, jugando |
| 15:00 | Reunión. No abre. La criatura duerme una siesta con la luz prendida. **3 errores de cuidado.** | — | ⚠ |
| 16:20 | Vistazo culposo. Apaga la luz, la despierta, le da de comer, la mima. | 90 s | Evoluciona a Adolescente ~16:40 |
| 19:00 | Ve la evolución en vivo: **Balanced**. Retrato, foto al álbum. | 3 min | Adolescente |
| 21:30 | Antes de dormir: come, baño con jabón, luz apagada. Lee el cuaderno del día. | 4 min | Adulto ~22:00 |
| 23:00–07:00 | **Ocho horas sin abrir.** Con las reglas actuales: muerta a las 23:50 por inanición. | — | ✕ **Este es el problema** |

Ese renglón final no es un detalle de balance: **rompe el juego**. Un producto cuya criatura no
sobrevive una noche de sueño humano no es un juego sobre la atención, es un juego sobre la
imposibilidad de dormir. Ver §8.1 y §9 R1.

Cómo debería terminar la tabla, con la ventana de gracia propuesta:

| Hora | Qué pasa |
|---|---|
| 23:00 | Luz apagada. La criatura duerme; el mundo entra en **reposo**: las necesidades siguen bajando pero la salud no se toca mientras duerme y está en la noche de su reloj. |
| 07:40 | El jugador abre. La criatura está famélica, sucia y de mal humor — pero **viva**. La reparación lleva dos minutos y se siente como un reencuentro, no como una autopsia. |

---

## 6. La historia y el mundo

Lore ligero. No hay profecías, no hay elegidos, no hay guerra. Todo lo de abajo tiene que caber
en 40 palabras dichas por una máquina.

### 6.1 La consola: el Vivario

La consola no es un teléfono con un juego adentro. Es un **Vivario**: un aparato doméstico,
fabricado en serie, cuyo único propósito es sostener una vida pequeña. Rieles laterales de
color, pantalla con bisel, altavoz que zumba un poco. Se compra en cualquier lado. No es mágico
y no es raro: es un electrodoméstico afectivo, como una incubadora o una pecera.

Eso justifica todo el estilo visual: las scanlines, los blips, las barras. **La criatura es
real; la interfaz es del aparato.** El jugador nunca mira a la criatura directamente — la mira
a través de una pantalla que la traduce a barras. Esa distancia es tema, no limitación.

### 6.2 De dónde salen los huevos

Los huevos vienen del **Vivero**, un lugar del que el juego habla poco y nunca muestra. Nadie
sabe bien qué son estas criaturas ni de dónde salieron; se sabe que llegan en cápsulas, que
crecen si se las cuida y que no crecen dos veces igual. La ciencia del asunto es aburrida y no
importa. Lo que importa es la parte que sí se dice: **el Vivero manda un huevo sólo cuando hay
alguien dispuesto a mirarlo.**

En la generación 2 en adelante el huevo no debería salir de un menú: debería **llegar**. Una
cápsula que aparece en el cuarto vacío al día siguiente. El jugador la abre o no la abre.

### 6.3 El cuidador (el "keeper")

El jugador es el **cuidador**. Es la única palabra que el juego usa para nombrarlo, y define su
relación entera: no es dueño (la criatura no es propiedad), no es entrenador (no la está
preparando para nada), no es padre (no hay familia). Es alguien que se ocupa.

El nivel de cuidador (`level`, `xp`) mide **cuánto tiempo llevás ocupándote**, no cuán bueno
sos. Nunca lo llamamos "rango" y nunca lo comparamos con nadie.

La criatura sabe que existís, pero **no sabe qué sos**. Nunca dice "papá", "mamá", "amo" ni
"jugador". Dice **"vos"**. Esa ambigüedad es la que deja que cada jugador ponga lo suyo.

### 6.4 Las cuatro familias

Cuatro familias, cuatro temperamentos, cuatro maneras de necesitarte. Los sesgos numéricos
citados son los de `Species` en `Pet.kt`.

**Aqua** — `hambre ×0,9 · energía ×1,0 · juego ×1,1`
La familia del agua quieta. Aguantan el hambre mejor que nadie y se entusiasman fácil. Son
sociables sin ser pegajosas: se acercan al vidrio cuando abrís la app y se vuelven a lo suyo.
Aletas, branquias, movimiento ondulado. La elección "fácil" y la que menos castiga el olvido.
*Su gesto característico:* soplar una burbuja cuando está contenta.

**Ember** — `hambre ×1,2 · energía ×1,15 · juego ×1,0`
Combustión. Comen un 20 % más rápido que nadie y se agotan antes. Son la familia intensa: dan
mucho y piden mucho, y una tarde de descuido se les nota en el cuerpo. Cuernitos, brasa en el
pecho, andar impaciente. La familia que enseña disciplina a la fuerza.
*Su gesto característico:* la brasa del pecho se apaga a oscuras cuando tiene hambre.

**Leaf** — `hambre ×0,85 · energía ×0,85 · juego ×0,9`
Lentitud vegetal. Es la familia más resistente y la menos demostrativa: gasta poco, se entusiasma
poco, dura. Brote en la cabeza, movimientos de balanceo. Es la elección para el jugador que
quiere que el juego lo acompañe en vez de reclamarle, y la que mejor sostiene una generación
larga.
*Su gesto característico:* el brote de la cabeza crece con la edad y florece de anciana.

**Volt** — `hambre ×1,1 · energía ×1,3 · juego ×1,25`
Puro nervio. Se queda sin energía un 30 % más rápido que nadie, duerme muchísimo, y cuando está
despierta convierte cualquier juego en fiesta (el mayor bono de felicidad por jugar del juego).
Orejas grandes, chispas, parpadeo rápido. La familia de las siestas y los estallidos.
*Su gesto característico:* se le eriza el pelo un segundo antes de despertarse.

### 6.5 El álbum y el cuaderno

Son las dos memorias del aparato y son **distintas a propósito**:

- **El álbum** es lo que *vos* elegiste guardar (fotos, `snapshot()`) más lo que el aparato
  archivó solo (cada evolución). Es visual y es de afuera: cómo se veía.
- **El cuaderno** (`Chronicle`) es lo que *ella* escribió. Primera persona, sin fechas de
  calendario, sólo días-mascota. Es de adentro: cómo lo vivió.

La distancia entre los dos es donde vive el juego. El álbum puede tener una foto preciosa del
día 4; el cuaderno puede decir que el día 4 estuvo sola. Ninguno de los dos miente. Los dos
sobreviven a la criatura y pasan a la siguiente generación, y por eso los dos necesitan estar
**particionados por generación** y no recortarse en silencio (§8.7, §9 R2).

---

## 7. Escritura y voz

### 7.1 Las tres voces

El juego tiene tres voces y **nunca se mezclan**:

**1. La criatura (el cuaderno, y sólo el cuaderno).**
Primera persona, presente o pasado cercano. Frases cortas. Vocabulario de alguien que aprendió
las palabras hace poco. Dice lo que le pasó y lo que sintió; **nunca dice lo que vos deberías
haber hecho**. No usa signos de exclamación salvo alegría genuina. No hace preguntas retóricas.
No amenaza con el futuro ("si te vas otra vez...") jamás.

**2. El aparato (toasts, botones, avisos, HUD).**
Neutro, breve, funcional, minúscula. Informa hechos. No opina, no felicita, no reta. Es una
máquina bien hecha: dice "sala limpia", no "¡bien hecho!". Es el único lugar donde puede haber
números.

**3. El narrador (memorial, hitos, primera vez).**
Aparece cinco o seis veces por partida y punto. Tercera persona, calmo, casi documental. Es la
voz que dice "vivió quince días". No es poético. Su fuerza está en decir poco.

### 7.2 Reglas duras

- **La criatura no se refiere al jugador por rol.** Siempre "vos". Nunca "papá/mamá/amo/dueño".
- **Nunca hay una barra de "te queda poco".** No hay cuentas regresivas hacia el castigo.
- **Nunca se usa el miedo a perder progreso.** Nada de "vas a perder tu racha", "no dejes que
  se rompa", "última oportunidad".
- **Nunca hay urgencia falsa.** Si el estado no cambió, no hay notificación. Una notificación
  por ciclo del worker, como máximo, y sólo si hay algo real.
- **Nunca se cuantifica el afecto en la cara del jugador.** El vínculo puede ser un número
  interno; en pantalla es una postura.
- **El error se nombra, no se juzga.** "Estuvo con hambre" ✓. "La dejaste con hambre" ✗.
- **Nada de humor que rompa la ficción.** Sin memes, sin guiños al jugador, sin cuarta pared.
- **Todo texto es traducible.** Nada de concatenar frases con fragmentos; una cadena, un
  significado (hoy esto no se cumple, §8.8).

### 7.3 Diez líneas correctas

| # | Línea | Por qué funciona |
|---|---|---|
| 1 | *"Abrí los ojos. Lo primero que vi fuiste vos."* | Cuaderno. Establece la relación en once palabras y sin pedir nada. |
| 2 | *"No me siento bien. Ojalá lo notes pronto."* | Dice su experiencia y su esperanza, no tu deber. La distancia entre "ojalá lo notes" y "¡atendeme!" es todo el juego. |
| 3 | *"La luz quedó prendida. Hice como que dormía."* | Concreta, específica, sin acusación. El jugador saca su propia conclusión, que es mucho peor y mucho mejor. |
| 4 | *"Sala limpia."* | Voz del aparato. Dos palabras, cero celebración. Confirma sin premiar. |
| 5 | *"Ya soy grande — de las tranquilas, parece. Eso fue cosa tuya."* | Cuaderno en la evolución. Atribuye el resultado al jugador **sin valorarlo**. |
| 6 | *"Está llena."* | Rechazo cómico. Un hecho neutro dicho por la máquina; ni chiste ni reproche. |
| 7 | *"Se da vuelta."* | Rechazo por tristeza. No explica la mecánica. Obliga a mirar. |
| 8 | *"Me crujen las rodillas. Me gané cada crujido."* | Vejez con dignidad y humor propio. La criatura no le tiene miedo a su edad. |
| 9 | *"Estuviste fuera 3 h 20 min. Durmió dos veces y se ensució una."* | Tarjeta de reencuentro. Hechos en pasado, sin adjetivos ni signos. |
| 10 | *"Viví quince días y todos fueron tuyos."* | Narrador/cuaderno en la muerte por vejez. Es un agradecimiento, no un reclamo. Cierra el juego. |

### 7.4 Cinco líneas prohibidas

| # | Línea | Por qué está mal |
|---|---|---|
| 1 | *"¡Tu mascota se está muriendo! ¡Entrá ya!"* | Urgencia manufacturada + imperativo. Convierte el cuidado en pánico y el pánico en desinstalación. |
| 2 | *"¿Por qué me dejaste sola?"* | La criatura acusa. En el momento en que reprocha, deja de ser un ser vivo y se vuelve una palanca de retención. |
| 3 | *"¡Racha de 6 días! No la pierdas."* | Mecánica de pérdida disfrazada de logro. Premia la ansiedad, no el cariño, y castiga vivir. |
| 4 | *"Cuidado deficiente: 14 errores. Calificación: D."* | Le pone nota a la relación. El juego no es un examen y el jugador no rindió nada. |
| 5 | *"Tu mascota murió. Abrí la app para empezar una nueva generación."* | Un duelo usado como llamada a la acción. Es la línea más dañina posible y **hoy existe en el código** (`Notifications.careMessage`). |

### 7.5 Longitudes

| Superficie | Máximo | Tono |
|---|---|---|
| Toast | 40 caracteres | aparato |
| Burbuja de necesidad | 1 palabra | aparato |
| Notificación | 60 caracteres, sin signos de exclamación | aparato |
| Línea de cuaderno | 90 caracteres, una o dos oraciones | criatura |
| Tarjeta de hito | 140 caracteres | narrador |
| Memorial | 3 líneas | narrador |

---

## 8. Qué se opone a la tesis

Lista honesta de lo que hoy trabaja en contra, verificado contra el código. Ordenada por daño.

### 8.1 La criatura no sobrevive una noche · **CRÍTICO**

**Qué pasa.** Saciedad llena a 0 en 13–26 minutos según etapa y especie. Con la saciedad en 0,
la salud baja `0,055/s` → 30 minutos más. **Muerte entre 43 y 56 minutos de ausencia**, con
todas las barras llenas al empezar. Dormida el desgaste de saciedad es ×0,4, lo que estira el
total a ~1 h 20 min. `maxOfflineSeconds = 12 h` no protege nada: simula las 12 horas completas.

**Por qué es fatal.** El texto de Ajustes dice literalmente *"Offline progress is capped at 12
hours so a long break never wipes a healthy pet"*. Es falso. Ocho horas de sueño humano matan
cualquier mascota sana. La única forma de llegar a `OLD_AGE` es no dormir cinco horas seguidas.

**Recomendación.** Introducir **reposo**: mientras la criatura duerme *y* es de noche en su
reloj *y* la luz está apagada, la salud no baja aunque la saciedad esté en 0 (las necesidades sí
siguen cayendo). Además, un tope de daño offline: una ausencia no puede llevarse más del 60 %
de la salud, sin importar cuánto dure. Resultado: volver después de ocho horas produce una
criatura arruinada pero viva, que es exactamente el sentimiento que el juego quiere.
La muerte debe requerir **negligencia repetida**, no una noche.

### 8.2 `Feral` es el resultado por defecto, y es un insulto · **ALTO**

**Qué pasa.** `decideBranch()` evalúa `FERAL` primero: `neglect >= 6/hora || discipline < 15`.
La disciplina arranca en 20 y cae `0,004/s` despierta = **14,4 puntos por hora**. Un jugador
que nunca toca "Felicitar" ni "Regañar" cruza el umbral de 15 en ~21 minutos de vigilia, mucho
antes del veredicto del minuto 61,5. Además, cada minuto de siesta con la luz encendida suma un
`careMistake`: dos siestas por hora ≈ 10 errores/hora ≥ 6 → `FERAL` otra vez.

**Por qué está mal.** El resultado más común del juego se llama "Salvaje", es el único que suena
a fracaso, y se obtiene por omisión de dos botones que el juego nunca explica. La rama que
debería significar "aprendió a arreglárselas" significa en la práctica "no encontraste el menú".

**Recomendación.** Tres cosas: (a) que la disciplina no decaiga sola, o que decaiga la décima
parte; (b) sacar `FERAL` del primer lugar del `when` y hacerlo requerir negligencia *sostenida*
y verificable, no un umbral pasivo; (c) renombrar y redibujar la rama para que sea deseable —
independiente, montaraz, autosuficiente. Que alguien la quiera a propósito.

### 8.3 El regaño es la vía óptima, y la predicción es un spoiler · **ALTO**

**Qué pasa.** `scold()` da **+10 de disciplina** por −6 de felicidad y −1 de vínculo, y su
condición de permiso (`discipline < 60`) está casi siempre activa. `praise()` da **+2**. Para
llegar a `SCHOLAR` (disciplina ≥ 60 y 8 elogios) la ruta eficiente es regañar cinco o seis veces
seguidas. En paralelo, la pantalla de estadísticas muestra `Next form = Simulation.decideBranch(pet)`
en vivo desde el minuto 2, o sea que el jugador ve "Feral" como pronóstico y tiene un botón que
lo arregla: retar a la criatura.

**Por qué está mal.** El juego enseña que la manera de criar bien es castigar seguido, y le
spoilea el clímax al jugador para empujarlo a hacerlo.

**Recomendación.** La disciplina debe medir **constancia**, no represión: alimentar antes de que
llegue a hambre crítica, apagar la luz de noche, curar dentro de los tres minutos. `scold()`
pasa a ser un gesto raro y de bajo efecto. Y `Next form` se saca de la pantalla de estadísticas
o se reemplaza por una pista cualitativa sin nombre de rama ("últimamente duerme mucho").

### 8.4 El memorial le pone nota a un duelo · **ALTO**

**Qué pasa.** `MemorialScreen` imprime `"${pet.mealsEaten} meals · ${pet.gamesWon} wins ·
${pet.careMistakes} care mistakes"` y pone **"Raise generation N+1"** como botón primario, con
"Stay a moment" abajo como secundario. La lápida es idéntica para vejez y para inanición.

**Recomendación.** Sacar `careMistakes` de esta pantalla (que viva en el cuaderno, donde tiene
voz y contexto). Invertir la jerarquía de botones: quedarse es lo primario, y el botón de
siguiente generación aparece recién a los diez segundos. Dos tratamientos visuales según
`DeathReason`. Y agregar la última línea del cuaderno, que es lo único que el jugador va a
recordar.

### 8.5 La notificación de muerte es un anzuelo · **ALTO**

**Qué pasa.** `Notifications.careMessage()` devuelve, si la mascota está muerta:
*"Your pet has passed away. Open the app to start a new generation."*

**Recomendación.** Borrarla. Si hay que avisar algo, se avisa una sola vez y sin llamada a la
acción. El reenganche jamás se construye sobre una muerte.

### 8.6 El paso de generación pierde y recorta memoria · **MEDIO**

**Qué pasa.** `nextGeneration()` **no** hereda `highScores`, y el `chronicle` heredado está
capado a 120 entradas con `takeLast()`. O sea que las primeras líneas de la generación 1 —la
eclosión, el "lo primero que vi fuiste vos"— se borran solas cuando la generación 2 escribe
suficiente. El álbum tiene el mismo problema en `snapshot()` (§8.7).

**Recomendación.** Particionar cuaderno y álbum **por generación**, con un tope por generación
en vez de un tope global, y marcar como no-borrables las entradas de tipo `MILESTONE` y `LOSS`.
Los récords de minijuegos se heredan como "récord de la casa" con el nombre de quién lo hizo.

### 8.7 El álbum borra el recuerdo más viejo en silencio · **MEDIO**

**Qué pasa.** `CareActions.snapshot()` guarda `(state.album + entry).takeLast(60)`. Las entradas
de evolución que agrega `handleEvolution()` no tienen tope, pero **sí caen dentro del recorte**
de las fotos manuales.

**Recomendación.** Nunca borrar automáticamente. Si hay límite técnico, subirlo mucho y avisar
antes; y si hay que sacrificar algo, que el jugador elija. Un juego sobre la memoria no puede
tener un `takeLast()` sobre la memoria.

### 8.8 El juego habla en inglés desde el código · **MEDIO**

**Qué pasa.** `strings.xml` tiene 13 cadenas (nombres de botones). Todo el resto —toasts,
rechazos, cuaderno, memorial, tutorial, tienda, pantalla de estadísticas— está escrito a mano
en inglés dentro de los `.kt`, concatenado con `${state.name}`. `FEATURES.md` 10.4 afirma que el
contenido textual está centralizado y traducido; no lo está.

**Recomendación.** Es prerequisito de todo §7: mover cada cadena a recursos con plurales y
placeholders nombrados antes de contratar a nadie para escribir. Ninguna regla de voz se puede
aplicar a texto que vive esparcido en la lógica.

### 8.9 La calificación de cuidado insulta a un recién nacido · **MEDIO**

**Qué pasa.** `careScore` promedia seis stats incluyendo disciplina (arranca en 20) y vínculo
(arranca en 10). Una criatura recién nacida y perfectamente cuidada da `0,60` → **grado "C"**.

**Recomendación.** O se saca la calificación con letra (recomendado: es un juicio de valor sobre
una relación) o se calcula sólo sobre las necesidades atendibles (saciedad, felicidad, higiene,
salud) y se muestra como una palabra, no como una nota escolar.

### 8.10 La personalidad se decide antes de que el jugador exista · **BAJO**

**Qué pasa.** El comentario de `Personality` en `Pet.kt` dice *"rolled from the first hours of
care"*, pero `newGame()` la sortea con `Random(seed)` en el instante de crear la partida, con
`seed = nowMillis`.

**Recomendación.** Cumplir lo que el comentario promete: decidir la personalidad al eclosionar,
a partir de lo que hizo el jugador en los 90 segundos del huevo (¿lo tocó?, ¿apagó la luz?,
¿se quedó mirando?). Es barato, hace que el huevo tenga sentido (§4.1) y convierte el primer
minuto y medio en la primera decisión.

### 8.11 Los objetos prometen cosas que no hacen · **BAJO**

**Qué pasa.** `toy_drum` dice "Unlocks Rhythm Tap" y `toy_cards` "Unlocks Memory Match", pero
`GamesScreen` sólo consulta `CareActions.canPlay()` y nunca mira el inventario. `toy_ball` dice
que desbloquea "Ball Rally", que no existe.

**Recomendación.** Elegir: o los juguetes desbloquean de verdad (y entonces son un arco de
progresión temprana, bueno para la etapa Niño), o se reescriben las descripciones. Un objeto que
miente sobre lo que hace erosiona la confianza en todo el resto del texto.

---

## 9. Roadmap narrativo

Priorizado por cuánto acerca el producto a la tesis por unidad de esfuerzo. **S** = días,
**M** = una a dos semanas, **L** = más.

| # | Feature | Qué es | Por qué sirve a la tesis | Esf. |
|---|---|---|---|---|
| **R1** | **Reposo nocturno y tope de daño offline** | Mientras duerme de noche con la luz apagada, la salud no baja; una ausencia nunca se lleva más del 60 % de la salud. | Sin esto el juego no es sobre la atención: es sobre no poder dormir. Es el arreglo que hace posible todo lo demás. | **S** |
| **R2** | **Cuaderno completo** (ya en curso) | `Chronicle` con pantalla propia, particionado por generación, hitos no borrables, lectura tipo diario. | Es la única memoria en voz de la criatura. Convierte cinco horas de barras en un relato que sobrevive al desinstalado. | **M** |
| **R3** | **Disciplina por constancia** | La disciplina deja de decaer sola y sube por cuidar a tiempo (comer antes de hambre crítica, luz apagada de noche, curar en < 3 min). `scold()` pasa a gesto menor. | Elimina el regaño como estrategia óptima y hace que la rama premie presencia en vez de represión. | **M** |
| **R4** | **Rediseño del memorial** | Dos tratamientos según causa de muerte, sin contador de errores, "quedarse" como acción primaria, última línea del cuaderno en pantalla. | El latido más importante del juego hoy termina en una boleta. | **S** |
| **R5** | **El huevo interactivo** | 90 segundos donde se puede tocar, abrigar y apagar la luz; esas acciones deciden la personalidad al eclosionar. | Hace que el primer minuto y medio sea una relación en vez de una sala de espera, y cumple lo que el código ya promete. | **S** |
| **R6** | **Tarjeta de reencuentro** | Al volver de una ausencia larga, una tarjeta con hechos en pasado en vez de una avalancha de toasts. | Es el momento donde el juego decide si es honesto o culpógeno. Ahora mismo es ruido. | **S** |
| **R7** | **Carta de hito** | En cada evolución y en la vejez, la criatura escribe media página al cuidador, distinta según cómo se la crió. | Convierte el veredicto mecánico de `decideBranch()` en reconocimiento. Es el pago emocional de todo el arco medio. | **M** |
| **R8** | **Memoria activa** | La criatura recuerda cómo la criaron y lo demuestra: se acerca al vidrio si el vínculo fue alto, duda si estuvo mucho sola, busca su juguete favorito. | "Recordar" en conducta pesa diez veces más que recordar en texto. Es la tesis hecha animación. | **M** |
| **R9** | **Álbum sin recorte + postal** | Sacar el `takeLast(60)`, particionar por generación, exportar la postal como imagen. | Un juego sobre la memoria no puede borrar recuerdos en silencio; y una postal que se puede compartir es la única viralidad honesta que este juego admite. | **S** |
| **R10** | **Linaje** | Pantalla de árbol de generaciones: nombre, especie, rama, días vividos, causa, foto. Sin puntajes. | Es el metajuego de la generación 3+. Sin esto, morir y volver a empezar es repetición; con esto, es historia. | **M** |
| **R11** | **Ritual de despedida** | Al morir: silencio, luz que baja, la posibilidad de quedarse en la sala vacía. El huevo siguiente **llega** al día siguiente en vez de salir de un menú. | Le da al duelo la duración que necesita y quita el embudo de "siguiente generación" del momento del duelo. | **S** |
| **R12** | **Vejez con mecánicas propias** | Menos monedas y XP, más presencia: la anciana pide compañía en vez de comida, cuenta cosas, se cansa. Reloj honesto de cuánto le queda. | El cuidado sin recompensa es el gesto más limpio del juego. Hoy la vejez es sólo "adulto con más riesgo de enfermarse". | **M** |
| **R13** | **Estaciones** | Cuatro estaciones ligadas a los días-mascota que repintan la sala, la ventana y el ánimo. | Da textura al vacío del arco adulto y hace que volver tenga novedad sin inventar tareas. | **M** |
| **R14** | **El objeto favorito** | En algún momento la criatura elige un objeto del inventario y lo adopta. Aparece en el cuarto, en las fotos, y se hereda a la generación siguiente. | Un detalle irracional y no optimizable es lo que hace que una criatura se sienta particular en vez de configurada. | **S** |
| **R15** | **Rechazos con carácter** | Cada personalidad rechaza distinto: Shy se esconde, Brave planta cara, Greedy come igual y se arrepiente. | El primer "no" es un latido (§4.2) y hoy es un toast genérico. Es la forma más barata de dar interioridad. | **S** |
| **R16** | **Internacionalización real** | Todas las cadenas a recursos, con plurales y placeholders nombrados; español como idioma de primera clase. | Prerequisito duro de §7. No se puede dirigir la voz de un juego cuyo texto vive dentro de la lógica. | **M** |
| **R17** | **Modo sin barras** | Un ajuste que oculta todo el HUD numérico: sólo la criatura y la sala. | Prueba de fuego del arte y regalo para el jugador que ya entendió el juego. Si funciona, confirmamos la tesis; si no funciona, sabemos qué arreglar. | **S** |
| **R18** | **Rutina aprendida** | La criatura aprende a qué hora sueles aparecer y empieza a esperarte a esa hora (se despierta, mira hacia afuera). Nunca reclama si no vas. | Convierte la costumbre del jugador en algo que la criatura reconoce. Es lo más cerca que el juego puede estar de ser correspondido. | **L** |
| **R19** | **Visita** | Otro Vivario (partida exportada) visita la sala un rato: las dos criaturas se miran, sale una foto para los dos álbumes. Sin servidor, sin ranking, sin combate. | Socialidad sin competencia. Cualquier ranking rompería la tesis en el acto; una foto compartida la refuerza. | **L** |
| **R20** | **Cápsula del tiempo** | Al morir de vejez, el cuidador puede guardar una cosa (una foto, una línea, el objeto favorito) que la próxima criatura encuentra en el cuarto y no entiende. | Cierra el círculo entre generaciones con un gesto de memoria, no de progresión. Es el mejor final que este juego puede tener. | **S** |

### Orden sugerido de ejecución

1. **Bloque de salvamento** (R1, R4, R5, R6, R9): arregla lo que hoy contradice activamente la
   tesis. Todo S salvo nada. Sin esto, cualquier contenido nuevo se construye sobre arena.
2. **Bloque de voz** (R2, R16, R7, R15): le da al juego una boca. R16 antes que cualquier
   contratación de escritura.
3. **Bloque de presencia** (R8, R12, R13, R14, R17): llena el hueco del arco adulto y de la vejez.
4. **Bloque de linaje** (R10, R11, R20): construye el metajuego de la generación 3+.
5. **Bloque largo** (R18, R19): sólo cuando todo lo anterior esté firme.

---

## Apéndice — Números de referencia rápida

Para artistas y escritores que necesiten saber "cuánto dura esto".

| Pregunta | Respuesta |
|---|---|
| ¿Cuánto vive una mascota? | 5 h 01 min de reloj real · 15 días-mascota (ritmo por defecto) |
| ¿Cuánto dura un día-mascota? | 20 min reales (ajustable 2–60) |
| ¿Cada cuánto hay que darle de comer? | cada 13–26 min según especie y etapa |
| ¿Cada cuánto duerme? | siestas de ~4,7 min, cada 15–25 min |
| ¿Cada cuánto hace popó? | ~1 cada 14 min bien alimentada · máximo 6 acumulados |
| ¿Cuándo se decide la forma adulta? | minuto 61,5 (Adolescente) y de nuevo en el minuto 121,5 (Adulto) |
| ¿Cuánto aguanta sola? | **43–56 min hasta morir** hoy · debería ser una noche entera (R1) |
| ¿Cada cuánto revisa el juego en segundo plano? | 15 min (mínimo de WorkManager), 1 notificación como máximo |
| ¿Cuánto progreso offline se simula? | 12 h como máximo |
| ¿Cuántos objetos, logros y minijuegos hay? | 24 objetos · 24 logros · 3 minijuegos · 5 cuartos · 5 sombreros |
