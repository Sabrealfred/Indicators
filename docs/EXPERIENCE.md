# NeoPal — Documento de experiencia

> Este documento es la columna vertebral de diseño. `FEATURES.md` dice **qué hay**; esto dice
> **por qué está** y **qué debe sentir el jugador**. Cuando una decisión de código, arte o texto
> entre en conflicto con este documento, se discute aquí primero.
>
> Todos los números citados están verificados contra `domain/Simulation.kt`, `domain/Pet.kt`,
> `domain/CareActions.kt`, `domain/Items.kt`, `domain/Achievements.kt`, `domain/Chronicle.kt`,
> `work/Notifications.kt`, `ui/screens/StatsScreen.kt` y `test/BalanceTest.kt` al día de
> escritura. Si el código cambia, este documento se corrige, no al revés.
>
> **Revisión 2 — post rebalanceo del ciclo de vida.** El simulador pasó de una vida de ~5 horas
> a una de ~48 horas y la ausencia dejó de ser mortal por hambre. Todo el §2, el §5, el §9 y el
> apéndice están recalculados. El §8 conserva los hallazgos viejos con su estado (resuelto /
> parcial / abierto) porque el registro de qué estaba mal es parte del valor del documento — y
> suma cinco hallazgos nuevos, tres de ellos graves, que el ritmo nuevo creó o destapó.

---

## 1. La tesis

Una mascota virtual no es un simulador de crianza. Es un aparato para fabricar **culpa
proporcional al cariño**, y ese es todo el truco.

El género se inventó con una premisa incómoda: te entrego una criatura que depende de que
mires una pantalla, y que se degrada esté o no mirando alguien. No hay pausa. No hay "guardar y
salir". El tiempo que pasás sin pensar en ella es tiempo que ella pasa esperándote. Esa es la
única mecánica que importa; todo lo demás —las estadísticas, las monedas, los minijuegos, los
sombreros— es andamiaje para que esa asimetría se pueda medir.

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
  contrario —y todavía lo sugiere, ver §8.2 y §8.12— está trabajando en contra de sí mismo.
- Un juego de optimización se termina. NeoPal termina siempre, y siempre igual: la criatura se
  muere. `DeathReason.OLD_AGE` no es una condición de derrota, es el final bueno. La partida
  perfecta también acaba en una lápida.

La mortalidad no es un castigo colgado al final del tubo: es lo que le da precio al minuto de
hoy. Sabemos, desde el segundo uno, que esto dura **unas 48 horas de reloj real** (171.990
segundos a ritmo Normal, §2). Nada de lo que hagas lo alarga. Lo único que podés decidir es
**cómo fueron esas dos jornadas** — y eso es exactamente lo que el álbum y el cuaderno existen
para guardar.

El rebalanceo cambió la escala pero no la tesis: la afiló. Una vida de cinco horas se podía
atravesar de una sentada, y una cosa que se atraviesa de una sentada es una sesión, no una
relación. Una vida de dos días **obliga a dormir en el medio**, y por lo tanto obliga al juego
a tener una postura sobre lo que pasa mientras no estás. Esa postura ahora está escrita en el
código, en `offlineDecayMultiplier` y `offlineHealthFloor`, y es la correcta: *perder una
mascota tiene que ser algo que hiciste, no algo que pasó mientras dormías.*

Corolario, y es la regla que más veces vamos a tener que defender: **NeoPal nunca debe usar la
culpa como palanca de retención.** La culpa es el material del que está hecho el juego, no la
herramienta con la que se lo vende. La notificación de muerte ya se corrigió ("X is gone.
Whenever you are ready.") y esa corrección es el modelo de todas las que faltan. El juego puede
— debe — dejar que la criatura diga que la pasó mal. No puede decirte que sos mala persona, y
no puede insinuar que si volvés más seguido eso se arregla.

Lo que queremos que quede después de desinstalar: no un puntaje. Una frase del cuaderno.

---

## 2. El arco de una vida

### 2.1 Los dos relojes

Después del rebalanceo hay **dos relojes independientes** y confundirlos es el error más fácil
de cometer al diseñar cualquier pantalla:

1. **El reloj de la vida.** `Simulation.baseStageSeconds()` mide cada etapa en **segundos
   reales**, no en días-mascota. `GameConfig.lifeSpeed` los divide.
   `Simulation.expectedLifetimeSeconds(config)` devuelve el total.
2. **El reloj del día.** `GameConfig.secondsPerPetDay = 7_200` (2 horas reales por día-mascota,
   ajustable de 5 min a 4 h). **Sólo** maneja el ciclo día/noche (`isNight`: de 21:30 a 6:30,
   o sea 45 minutos de cada 2 horas) y el contador de días. **No** hace crecer a nadie.

### 2.2 Las etapas (a `lifeSpeed = 1.0`, "Normal")

| Etapa | Base | Real | Edad acumulada | Día-mascota | Desgaste |
|---|---|---|---|---|---|
| Huevo | 90 s | 1 min 30 s | 0:01:30 | 0,0 | ×0 (nada decae) |
| Bebé | 45 min | 45 min | 0:46:30 | 0,4 | ×1,30 |
| Niño | 3 h | 3 h | 3:46:30 | 1,9 | ×1,10 |
| Adolescente | 8 h | 8 h | 11:46:30 | 5,9 | ×1,00 |
| Adulto | 24 h | 24 h | 1 d 11:46:30 | 17,9 | ×0,90 |
| Anciano | 12 h | 12 h | 1 d 23:46:30 | 23,9 | ×1,05 |

**Vida completa por vejez: 171.990 s = 47 h 46 min 30 s ≈ 2 días reales ≈ 24 días-mascota.**

La curva está deliberadamente cargada adelante y hay que entender por qué: **la primera
evolución cae a los 46 minutos y medio**, dentro de una única sesión larga. El juego le muestra
su promesa grande a un jugador nuevo antes de pedirle que vuelva mañana. Después las etapas se
estiran hasta que la etapa Adulto sola es la mitad de la vida.

### 2.3 Los presets de ritmo

`SettingsScreen` ofrece cuatro, y cada uno es un producto distinto:

| Preset | `lifeSpeed` | Vida completa | Para quién |
|---|---|---|---|
| Slow | 0,5 | **95 h 33 min ≈ 4 días** | el jugador que quiere una relación, no una partida |
| Normal | 1,0 | **47 h 47 min ≈ 2 días** | el default, y el que este documento asume |
| Fast | 3,0 | **15 h 55 min** | una vida entera entre hoy y mañana |
| Demo | 12,0 | **3 h 59 min** | pruebas, capturas, tienda de aplicaciones |

Advertencia de diseño: `lifeSpeed` alarga la vida pero **no toca el metabolismo**. Las tasas de
desgaste son constantes absolutas. En "Demo" la criatura vive 4 horas y hay que darle de comer
unas 12 veces; en "Slow" vive 4 días y hay que darle de comer unas 250. El ritmo de cuidado y
el ritmo de vida están desacoplados y hoy nadie los reconcilia (§8.15).

### 2.4 Ritmos de necesidad

Las constantes de desgaste **no cambiaron**. Lo que cambió es que ahora hay dos regímenes:
en vivo (app abierta, o hueco de menos de 180 s) y en ausencia (`decayScale = 0,30`).

| Necesidad | Tasa base (por segundo) | En vivo | En ausencia (×0,30) |
|---|---|---|---|
| Saciedad | `0,085 × etapa × hungerBias × (1,2 Greedy)` |  100 → 0 en **11–26 min** | **35 min – 1 h 27 min** |
| Energía | `0,050 × etapa × energyBias` | siesta cada **15–25 min** | cada **50–85 min** |
| Sueño | recupera `0,320` hasta 98 | siesta de **≈ 4,7 min** | ≈ 15 min |
| Felicidad | `0,045 × etapa × personalidad` | enfurruñe (<12) en **15–35 min** | **50 min – 1 h 55 min** |
| Higiene | `0,028 × etapa + 0,02 por popó` | "sucio" (<30) en **≈ 45 min** | ≈ 2 h 30 min |
| Disciplina | `0,0015` sólo despierta | **5,4 / hora** (era 14,4) | 1,6 / hora |
| Vínculo | `0,005` | 18 / hora | 5,4 / hora |
| Popó | `0,0009 × (0,5 + saciedad/100)` por s | ~1 cada **14 min** · tope 6 | ~1 cada 45 min |
| Enfermedad | chequeo cada 30 s, riesgo base 0,4 % | **≈ 38 % por hora** | igual (no escala) |

### 2.5 Qué pasa mientras no estás

Esta es la regla nueva y hay que saberla de memoria, porque reescribe la mitad del juego:

- Si el hueco supera **180 segundos**, la simulación entra en modo puesta al día: las
  necesidades caen al **30 %** de la tasa viva.
- La salud **no puede bajar de `offlineHealthFloor = 12`** — pero la protección se decide
  **una sola vez, con el estado en que la dejaste**: `protectHealth = isCatchUp && !isSick &&
  health > 12`. Si te fuiste con la mascota enferma, o con la salud ya por el piso, la ausencia
  puede matarla.
- **La edad, el sueño y la evolución corren a tiempo real, sin escalar.** Sólo las necesidades
  se ralentizan. Es decir: **la criatura crece mientras no estás.**
- `maxOfflineSeconds = 12 h` sigue capando el hueco simulado. Una semana afuera envejece a la
  mascota 12 horas y le cuesta lo mismo que una noche. La vida de 48 horas es **elástica**: se
  estira con tus ausencias largas.

Traducción emocional, que es lo que importa: **volver después de una noche encuentra una
criatura arruinada pero viva.** Famélica (saciedad en 0 desde hace horas), de mal humor, sucia,
y con la salud raspando el piso. Repararla lleva dos minutos. `BalanceTest` fija exactamente
eso: *"Surviving is not the same as being fine — the guilt has to survive the fix."* Es la
línea de diseño mejor escrita que hay en el repo y define el tono entero del juego.

Lo que **sigue matando**: la enfermedad que dejaste sin curar (§4.3, §8.13) y la negligencia
con la app abierta.

### 2.6 Huevo — 90 segundos

- **Qué siente el jugador:** impaciencia con permiso. Es el único momento en que no hacer nada
  es lo correcto, y hay que venderlo como tal.
- **Qué necesita la mascota:** nada. `stageMult = 0`: ninguna estadística se mueve.
- **Qué debe revelar el juego:** que el tiempo corre solo. El cascarón se agrieta por etapas
  aunque cierres la app; volver y encontrarlo más agrietado enseña la regla central sin una
  línea de tutorial.
- **La tensión:** ninguna, y está bien. Es el respiro antes del contrato.
- **La mecánica que lo sostiene:** el temporizador y la animación de grieta. Hoy
  `CareActions.guard()` bloquea absolutamente todo con "The egg is still warming up...", así que
  el primer minuto y medio de NeoPal es una pantalla que dice que no a todo (§9 R8).
- **Nota:** los 90 s **sí** escalan con `lifeSpeed` (`stageDuration` divide todo). En "Demo" el
  huevo dura 7 segundos, que es demasiado poco para que se lea como un nacimiento. Vale la pena
  ponerle un piso de ~20 s.

### 2.7 Bebé — 45 minutos

- **Qué siente el jugador:** susto útil, y después el primer triunfo. Es la etapa más exigente
  (`×1,30`): un Ember bebé vacía la saciedad en **12 min 34 s**, y con personalidad Greedy en **10 min 30 s**, así que en 45 minutos hay
  tres comidas, una siesta y probablemente un popó.
- **Qué necesita la mascota:** comida y presencia física. Al eclosionar arranca con
  `happiness = 80`, `satiety = 60` — no llena: **con hambre desde el primer segundo**.
  Es deliberado y es correcto.
- **Qué debe revelar el juego:** los cuatro botones que importan (comer, limpiar, luz, mimo) y
  el hecho de que la criatura **reacciona a que la toques** (`pet`, `tickle`, `toss`) sin que
  eso sea "productivo".
- **La tensión:** el jugador todavía no sabe cuánto tiempo tiene. Todo se siente urgente.
- **La mecánica que lo sostiene:** la duración. 45 minutos es exactamente una sesión larga de
  descubrimiento, y termina en una evolución. **La primera sesión de NeoPal contiene un ciclo
  completo: conocer, cuidar, ver crecer.** Ese es el diseño más importante del rebalanceo y hay
  que protegerlo de cualquier futuro ajuste.

### 2.8 Niño — 3 horas

- **Qué siente el jugador:** competencia, y el primer cambio de marcha. Tres horas es demasiado
  para quedarse mirando: acá es donde el jugador aprende a **irse y volver**.
- **Qué necesita la mascota:** variedad. Los minijuegos y la tienda empiezan a tener sentido:
  hay energía de sobra y monedas que gastar. Y son tres horas: entran unas 8 comidas.
- **Qué debe revelar el juego:** que la ausencia es normal y no es letal. Es la primera vez que
  el jugador cierra la app con una criatura viva y la encuentra viva. Ese descubrimiento merece
  ser explícito: la tarjeta de reencuentro (§9 R3) debería aparecer por primera vez acá.
- **La tensión:** invisible y por eso peligrosa. `careMistakes`, `praises`, `gamesPlayed`,
  `weightGrams` y `mealsEaten` se están acumulando calladamente hacia el veredicto de las
  3 h 46 min. Está bien que el jugador no lo sepa **con números**; está mal que no lo intuya.
  La criatura debería empezar a tener manías observables.
- **La mecánica que lo sostiene:** el peso corporal, único stat que ya se ve en la silueta.
  Debería haber tres o cuatro más de esos.

### 2.9 Adolescente — 8 horas

- **Qué siente el jugador:** el primer orgullo, y el primer arrepentimiento. A las **3 h 46 min
  30 s** se ejecuta `decideBranch()` por primera vez y la criatura sale de la pantalla blanca
  siendo alguien.
- **Qué necesita la mascota:** menos comida (`×1,00`) y más interacción. La felicidad y el
  vínculo pesan más que la saciedad.
- **Qué debe revelar el juego:** el veredicto. Es el clímax medio de la partida y hoy se
  entrega como un flash blanco y un toast. Merece un texto: **una línea del cuaderno en primera
  persona** que explique la forma en términos de lo que pasó, no de umbrales.
- **La tensión estructural nueva:** ocho horas es una noche de sueño. Un jugador que empieza a
  la tarde **pasa toda la etapa adolescente durmiendo**, y el segundo `decideBranch()` (el
  definitivo, en el salto a Adulto a las 11 h 46 min) se ejecuta con él inconsciente. La
  segunda oportunidad existe y es invisible por partida doble: el juego no la menciona y encima
  la resuelve mientras no mirás (§8.14).
- **La mecánica que lo sostiene:** `decideBranch()` es 100 % determinista. Dos jugadores que
  crían igual obtienen la misma criatura. Eso es un valor y hay que protegerlo.

### 2.10 Adulto — 24 horas

- **Qué siente el jugador:** calma, y después aburrimiento si no hacemos nada. Es **la mitad de
  la vida entera** en una sola etapa, con el menor desgaste (`×0,90`) y ninguna transformación
  a la vista.
- **Qué necesita la mascota:** compañía sin urgencia. Es la primera vez que se la puede dejar
  sola cuatro horas sin drama.
- **Qué debe revelar el juego:** la rutina como forma de cariño. Aquí es donde el juego deja de
  pedir y empieza a **acompañar**: es el territorio natural de las estaciones, las fotos, el
  cuaderno, los sombreros, los hitos diarios.
- **La tensión:** el riesgo real es el olvido. Un adulto sano no te llama, y un jugador que no
  es llamado se va. La respuesta correcta **no** es subir el desgaste ni inventar urgencia: es
  darle a la criatura cosas que ofrecer.
- **La mecánica que lo sostiene:** hoy, ninguna. **Este es el hueco más grande del producto y
  el rebalanceo lo multiplicó por cinco** — antes eran 100 minutos, ahora son 24 horas. Con el
  reloj de 2 h por día-mascota, dentro de la etapa Adulto pasan **12 días-mascota**: hay una
  estructura natural de doce capítulos esperando que alguien la use (§9 R7).

### 2.11 Anciano — 12 horas

- **Qué siente el jugador:** ternura y anticipación del duelo. Sabe lo que viene.
- **Qué necesita la mascota:** cuidados otra vez, pero por otro motivo. El desgaste sube a
  `×1,05` y `handleSickness()` le suma un **+2 % fijo de riesgo por chequeo** por ser Elder.
  Se enferma más y se recupera peor. Con 12 horas de etapa, eso significa varias enfermedades
  garantizadas, y **cada una es potencialmente mortal si te agarra yéndote a dormir** (§8.13).
- **Qué debe revelar el juego:** que el cuidado ya no construye nada. No hay evolución después.
  Alimentarla en la vejez es puro cuidado sin retorno, y ese es el gesto más limpio del juego.
  Hay que dejar que se sienta así: **menos monedas y menos XP en esta etapa**, no más.
- **La tensión:** el reloj de 12 horas corre aunque hagas todo perfecto.
- **La mecánica que lo sostiene:** `handleDeath()` mata por `OLD_AGE` en cuanto
  `secondsInStage >= stageDuration(ELDER)`, y `BalanceTest` fija que **la vejez llega aunque
  hayas estado afuera**. Es inevitable por diseño, y está bien. El jugador debería poder **ver**
  cuánto queda para poder despedirse a tiempo.

### 2.12 Muerte

Cuatro salidas: `STARVATION`, `ILLNESS`, `NEGLECT`, `OLD_AGE`. Las tres primeras son fallos del
jugador; la cuarta es el final del cuento. Después del rebalanceo la distribución real cambió:

- `STARVATION` y `NEGLECT` ahora requieren **negligencia con la app abierta** — un jugador
  activo que ignora una criatura famélica. Es raro y es justo.
- `ILLNESS` es la única que todavía mata en ausencia, y hoy mata demasiado (§8.13).
- `OLD_AGE` debería ser el desenlace mayoritario. Ese es el objetivo del balance y todavía no
  se cumple.

**El juego debe tratar las cuatro distinto y hoy no lo hace** (§8.4): mismo memorial, misma
tipografía y mismo botón para "murió de vieja a los dos días" que para "la dejaste morir".

### 2.13 Qué se ve en cuánto tiempo

| Si el jugador juega... | Ve | Se pierde |
|---|---|---|
| **45 minutos** (una sesión) | eclosión, la etapa bebé entera, **la primera evolución** | — |
| **Una tarde (4 h)** | + la niñez completa y **el primer veredicto de rama** (Adolescente, 3:46) | nada esencial |
| **Una tarde y una noche (12 h)** | + el salto a Adulto y **el veredicto definitivo**… si está despierto | el segundo `decideBranch()` ocurre a las 11:46 — casi seguro dormido |
| **Dos días** | la vida entera: 6 etapas, ~24 días-mascota, memorial por vejez | — |
| **Dos días con ausencias > 12 h** | lo mismo, pero estirado: el tope de puesta al día "regala" tiempo | — |

---

## 3. El arco entre generaciones

### 3.1 Qué se hereda hoy (verificado en `Simulation.nextGeneration`)

| Se hereda | No se hereda |
|---|---|
| `coins` | `level` y `xp` (vuelven a 1 / 0) |
| `album` completo | `highScores` de los minijuegos |
| **`chronicle` completo** (nuevo) | `personality` (se vuelve a sortear) |
| `unlockedAchievements` | `weightGrams`, todos los contadores de cuidado |
| Inventario **cosmético** (sombreros, cuartos) | Consumibles (se reponen 3 bayas, 2 platos, 1 pastilla) |
| `generation + 1` | El nombre |

### 3.2 Por qué la segunda generación debe sentirse distinta

Con una vida de dos días, llegar a la generación 2 es un compromiso de fin de semana, no de una
tarde. El peso de esa decisión sube muchísimo, y el juego tiene que estar a la altura:

1. **Ya no hay excusa.** En la gen 1 el jugador no sabía que un adulto aguanta cuatro horas
   solo. En la gen 2 lo sabe. Las mismas acciones significan otra cosa cuando son informadas.
2. **La casa ya no está vacía.** Hereda monedas, sombreros y cuartos: la nueva criatura nace en
   el mundo que construyó la anterior. Eso hoy es sólo economía; debería ser escenografía — el
   sombrero de la mascota muerta debería estar colgado en el cuarto, no sólo en el inventario.
3. **El cuaderno ya tiene voz.** Es la novedad más grande respecto de la revisión anterior:
   la generación 2 nace con las palabras de la generación 1 en el bolsillo. Eso es exactamente
   el sentido de linaje que queríamos, y todavía no está presentado como tal (§8.6).
4. **La progresión se reinicia y eso es correcto.** Perder el nivel de cuidador dice: la
   experiencia no se transfiere, el vínculo sí. Es exactamente la tesis. Lo que **no** es
   correcto es perder los récords de los minijuegos sin decírselo a nadie.

### 3.3 El juego largo (generación 3+)

A partir de la tercera, la unidad narrativa deja de ser la vida y pasa a ser **la casa**. El
jugador ya no pregunta "¿cómo hago para que llegue a anciana?" sino "¿qué clase de cuidador
soy?". El juego debe poder contestarlo:

- **El linaje visible.** Un árbol de generaciones: nombre, especie, rama, días vividos, causa
  de muerte, una foto. Sin puntajes ni comparaciones. Es un cementerio amable y es el verdadero
  metajuego.
- **Repetición con variación.** Cuatro especies × cinco ramas × cinco personalidades = 100
  retratos posibles. El coleccionismo aquí es legítimo *si* el juego no lo convierte en una
  checklist con casillas vacías gritando.
- **El eco.** La generación N debería poder heredar **una** cosa concreta e irracional de la
  N−1: un juguete favorito, una manía, una frase que reaparece en el cuaderno. Nada que altere
  estadísticas. Sólo memoria.
- **El final honesto.** El juego no tiene un final y no debe fingir uno. Lo más cerca de un
  cierre es la carta que escribe una anciana antes de morir (§9 R5). Después de eso, seguir es
  elección, no obligación.

---

## 4. Los latidos emocionales

Ocho momentos. Si estos ocho funcionan, el juego funciona, aunque el resto sea mediocre. Si uno
falla, no hay contenido que lo compense.

### 4.1 La primera eclosión (minuto 1:30)

- **Qué debe sentir:** que algo cruzó desde el otro lado del vidrio.
- **Qué debe hacer la app:** el cascarón se agrieta en tres pasos visibles — el jugador debe
  *ver venir* el momento y quedarse mirando. Explosión de partículas, sonido de eclosión, y
  después **dos segundos de silencio absoluto** con la criatura sola en pantalla, sin HUD, sin
  barras, sin toast. La primera línea del cuaderno ya existe y es la correcta: *"Abrí los ojos.
  Lo primero que vi fuiste vos."* Las barras aparecen recién después.
- **Error a evitar:** mostrar el logro "It moved!" encima de la eclosión. El banner tapa el
  momento. Que espere cinco segundos.

### 4.2 El primer rechazo de comida

- **Cuándo:** `CareActions.feed()` bloquea si `isSulking` (felicidad < 12) o si la saciedad
  llegó a 96. Son dos rechazos distintos y hoy suenan casi igual.
- **Qué debe sentir:** desconcierto, y después entendimiento. "No come porque está *triste*"
  es la primera vez que el jugador piensa en la criatura como sujeto y no como barra.
- **Qué debe hacer la app:** el rechazo por enfurruñe necesita animación propia — se da vuelta,
  no una negación genérica — y una línea que **no explique la mecánica**: "se da vuelta" es
  mejor que "necesita más felicidad para comer". Y la solución (mimar, jugar, y recién después
  dar de comer) tiene que funcionar en menos de un minuto, o el descubrimiento se vuelve
  frustración.
- **Nuevo con el ritmo de dos días:** este momento ahora ocurre casi siempre **al volver de una
  ausencia larga**, con la felicidad raspando el piso. Es el primer gesto del reencuentro y hay
  que tratarlo como tal: la criatura no te castiga, está mal. Mimarla primero es la lección.

### 4.3 La primera enfermedad

- **Cuándo:** `handleSickness()` chequea cada 30 s; con la sala limpia el riesgo base es 0,4 %
  por chequeo, o sea **≈ 38 % por hora**. En una vida de 48 horas eso significa **más de una
  docena de enfermedades por partida**. Ya no es "la primera enfermedad": es una rutina.
- **Qué debe sentir:** alarma proporcionada. En vivo, la enfermedad baja la salud a
  `0,055 × 0,6 = 0,033/s` → ~50 minutos desde salud llena. En ausencia, `×0,30` → **2 h 48 min,
  y sin piso de protección**. Ahí está la única muerte que todavía te agarra durmiendo.
- **Qué debe hacer la app:** cambiar el ambiente, no poner un icono. El aura verde ya existe;
  hay que sumarle que la criatura **deje de hacer cosas** — sin idle animado, sin reaccionar a
  los mimos con la misma alegría. Y el cuaderno escribe, en ese instante, *"No me siento bien.
  Ojalá lo notes pronto."* — que ya existe, y es exactamente el registro correcto: dice su
  experiencia, no tu obligación.
- **La curación tiene que ser un evento.** `medicine` cura con `happiness = −6`: se toma la
  pastilla con cara de asco. Eso es oro; hay que animarlo. `medicine_super` cura con `+6`.
  Dos animaciones distintas.
- **Y hay que recalibrar el riesgo.** Una tasa pensada para una vida de cinco horas, aplicada a
  una de cuarenta y ocho, convierte el evento raro en ruido de fondo (§8.13).

### 4.4 La primera evolución revelada (minuto 46:30)

- **Qué debe sentir:** que el resultado es suyo. No sorpresa aleatoria: **reconocimiento**.
- **Cuándo:** la primera (Bebé → Niño) cae dentro de la primera sesión, garantizada. Ese es el
  gran acierto del rebalanceo y hay que apoyarse en él: **la promesa del juego se cumple antes
  de que el jugador tenga que decidir si vuelve mañana.**
- **Qué debe hacer la app:** el estirón + fogonazo + forma nueva ya está. Falta lo que lo
  convierte en un latido: después del flash, una **tarjeta de retrato** con la forma nueva, su
  nombre, y una sola frase que ligue la forma a la crianza. Se guarda sola en el álbum
  (`handleEvolution` ya lo hace) y la app debe *mostrar que se guardó* — la miniatura vuela
  hacia el icono del álbum. Ahí es donde el jugador aprende que el álbum existe.
- **El spoiler ya se arregló.** La pantalla de estadísticas dejó de decir `Next form: Feral` y
  ahora dice `Leaning toward: wary, left to itself`. Es una pista cualitativa sin nombre de
  rama: exactamente lo que este documento pedía.
- **Lo que sigue roto:** las evoluciones 3, 4 y 5 caen a las 3:46, 11:46 y 35:46, y con etapas
  de 8 y 24 horas **la mayoría ocurre mientras la app está cerrada**. Hoy los eventos se
  disparan igual, en un lote, y `showToast()` cancela el anterior: el jugador abre la app y ve
  un solo toast superviviente de una noche entera de historia (§8.14).

### 4.5 Dejar la luz encendida

- **La mecánica:** si duerme con `lightsOff = false`, pierde `0,045 × 1,5 = 0,0675` de
  felicidad por segundo (≈ 4 puntos por minuto) y `handleCareMistakes()` registra un error de
  cuidado por minuto.
- **Qué debe sentir:** una punzada pequeña y específica. Es el mejor gesto del juego porque
  cuesta cero esfuerzo y cero recursos: sólo hay que **acordarse**. Es la tesis en un botón.
- **Qué debe hacer la app:** hacerlo legible sin regañar. La criatura se tapa la cara, se da
  vuelta, se mueve incómoda. Nada de texto rojo. Nada de "¡Estás lastimando a tu mascota!".
- **Nuevo peso con el ritmo de dos días:** apagar la luz antes de irse a dormir es ahora un
  **ritual real de fin de sesión**, alineado con el sueño del jugador. Es lo más cerca que
  NeoPal va a estar de tener un gesto de despedida, y merece su propia animación.
- **Ajuste que sigue pendiente:** una siesta por agotamiento a mediodía con la luz encendida
  acumula errores de cuidado invisibles que empujan a `FERAL` (§8.2, §8.12).

### 4.6 Volver después de estar afuera

**Con el ritmo nuevo este dejó de ser un latido más y pasó a ser el latido principal.** En una
vida de 48 horas el jugador va a hacer este gesto entre diez y veinte veces; la eclosión la
hace una sola.

- **Qué debe sentir:** alivio, o el peso de lo que pasó. Nunca una auditoría.
- **Qué debe hacer la app:** hoy `onResumed()` corre la simulación de golpe y descarga una
  avalancha de eventos, de los que sobrevive el último toast. En lugar de eso: **una tarjeta de
  reencuentro** que ordene la noche en tres o cuatro hechos. "Estuviste fuera 8 h 20 min.
  Durmió, se ensució dos veces, y **creció**." Hechos, en pasado, sin adjetivos. Si la pasó mal,
  se dice sin acusar: "estuvo con hambre unas cinco horas."
- **Y las revelaciones se sirven de a una.** Si evolucionó mientras no estabas, la escena de
  evolución se **guarda en cola** y se reproduce ahí, con el jugador mirando. Un momento que
  ocurrió sin público no ocurrió.
- **Lo que la criatura hace al verte:** si el vínculo es alto, corre hacia el vidrio. Si el
  vínculo cayó, tarda en acercarse. Eso es toda la información que el jugador necesita.
- **Nunca:** una cuenta de todo lo que hiciste mal. Nunca un "¡te extrañó!" con signos de
  exclamación cuando en realidad estuvo sola.

### 4.7 La pantalla de muerte

- **Qué debe sentir:** duelo real, en escala de juguete. No hay que exagerarlo ni escaparle.
  Y con dos días de vida invertidos, el duelo ahora tiene con qué sostenerse.
- **Qué debe hacer la app:**
  - **Silencio primero.** Sin música, sin toast, sin logro. La criatura se apaga y la pantalla
    se queda quieta unos segundos antes de que aparezca nada.
  - **Cuatro memoriales, no uno.** Vejez: cálido, dorado, "vivió veinticuatro días". Hambre /
    negligencia / enfermedad: frío, corto, honesto, sin sermón. Las líneas del cuaderno ya están
    escritas y son duras y correctas (*"Esperé una comida que no llegó"*). Eso es lo máximo que
    el juego puede decir; una palabra más y es manipulación.
  - **El botón de continuar no puede ser el primer botón.** Hoy "Raise generation N+1" es
    primario y "Stay a moment" es secundario. Hay que invertirlos.
  - **Nada de estadísticas de rendimiento.** El memorial actual sigue imprimiendo "N care
    mistakes". Es una boleta al pie de una lápida. Fuera.
- **La notificación de muerte ya se arregló** y es el modelo a seguir: *"X is gone. Whenever
  you are ready."* Sin llamada a la acción, sin embudo.

### 4.8 La primera foto del álbum

- **Qué debe sentir:** autoría. "Yo elegí guardar este momento."
- **Qué debe hacer la app:** el gesto ya es el correcto — mantener pulsada la criatura. Hay que
  vestirlo: cierre de obturador, congelamiento de un frame, el marco de la postal apareciendo
  alrededor. Y **dejar poner un título propio** (`snapshot()` ya lo acepta) con un texto
  sugerido, no impuesto.
- **La regla de oro:** el álbum no se ordena por calidad, no tiene rareza, no tiene estrellas y
  no se puede "completar". Es un álbum, no una colección.
- **El bug de memoria ya se arregló.** `CareActions.trimAlbum()` ahora descarta selfies antes
  que evoluciones, y `Chronicle.trim()` descarta días rutinarios antes que hitos. Lo que falta
  es la partición por generación: hoy las dos memorias son un pozo único y la generación 3 le
  come el archivo a la 1 (§8.6).

---

## 5. El bucle de sesión

### 5.1 Los dos modos

NeoPal tiene exactamente dos sesiones y hay que diseñar las dos, no una:

| | **El vistazo (30–60 s)** | **La visita (10–45 min)** |
|---|---|---|
| Frecuencia | 4–8 veces al día | 1–2 veces al día |
| Disparador | notificación, o costumbre | tiempo libre, o una evolución en cola |
| Qué hace el jugador | mira, corrige lo urgente, sale | juega, compra, decora, saca fotos, lee el cuaderno |
| Qué necesita | leer el estado en **menos de dos segundos** | tener algo que hacer que no sea mantenimiento |
| Éxito | salir sin culpa | salir con algo nuevo (una foto, una línea, una forma) |
| Fracaso de diseño | tener que abrir tres pantallas para dar de comer | que todo el contenido sea mantenimiento con otro nombre |

**Lo que debe ser cierto en los dos:**

1. **El estado se lee de la pantalla, no de las barras.** Si el jugador tiene que mirar números
   para saber cómo está, el arte falló. Postura, cara, sala, luz: eso primero.
2. **Ninguna acción esencial está a más de un toque de la pantalla principal.**
3. **Salir nunca es un error.** El juego no tiene "cerrá bien la app". Apagar la luz es una
   gentileza, no un checklist.
4. **El juego nunca inventa urgencia.** `Notifications.careMessage()` devuelve `null` cuando la
   mascota está bien, y el worker corre cada 15 minutos como máximo. Con el desgaste offline al
   30 %, eso ahora significa **una notificación cada varias horas**, no cada rato. Es la cadencia
   correcta y hay que defenderla.
5. **Cada visita deja rastro.** Una línea de cuaderno, una foto, una moneda.

### 5.2 La regla nueva: una noche es un capítulo

Antes del rebalanceo, ocho horas de sueño humano mataban a cualquier mascota sana: el juego era
literalmente incompatible con dormir. Ahora una noche es **la unidad narrativa natural del
producto**:

- La criatura **sobrevive** (piso de salud 12, y `BalanceTest` lo fija).
- La criatura **no está bien** (saciedad en 0, ánimo por el piso, sucia). Hay algo que reparar
  y repararlo se siente como un reencuentro.
- La criatura **creció**. La edad no se ralentiza: en ocho horas puede haber cambiado de etapa.
- La criatura **puede haberse enfermado y muerto** si la dejaste enferma, o si se enfermó
  temprano en la noche. Eso último todavía pasa demasiado (§8.13) y es lo próximo a arreglar.

De ahí sale la forma correcta del producto: **dos sesiones ancla por día** (mañana y noche),
con vistazos en el medio, y una vida que dura de una noche a la siguiente por dos veces.

### 5.3 Un día y medio en la vida (ritmo Normal, empezando un lunes 19:00)

| Hora real | Edad | Qué pasa | Sesión |
|---|---|---|---|
| Lun 19:00 | 0:00 | Elige especie y nombre. El huevo late. | inicio |
| 19:01:30 | 0:01:30 | **Eclosiona.** Silencio, partículas, primera línea del cuaderno. | — |
| 19:02–19:46 | | Etapa bebé completa: tres comidas, una siesta, un popó, dos partidas. Aprende todo el juego. | **44 min** |
| 19:46:30 | 0:46:30 | **Primera evolución → Niño.** Retrato, foto al álbum. La primera sesión cerró un ciclo entero. | — |
| 20:30 | | Vistazo. Come, limpia. | 40 s |
| 22:00 | | Sesión de sillón: compra el Gorro de Hoja, prueba el cuarto de playa, lee el cuaderno. | 12 min |
| 22:46:30 | 3:46:30 | **→ Adolescente.** Primer veredicto de rama: *Balanced*. Lo ve en vivo, de casualidad. | 3 min |
| 23:15 | | Le da de comer, la baña, **apaga la luz**. Cierra la app. | 90 s |
| 23:15–07:30 | | **8 h 15 min afuera.** Necesidades al 30 %: la saciedad llega a 0 alrededor de las 00:30 y se queda ahí. La salud baja hasta el piso de 12 y no más. **A las 07:01 evoluciona a Adulto** — el veredicto definitivo, sin público. | — |
| Mar 07:30 | 12:30 | Abre. Encuentra una adulta famélica, sucia y viva. Come, baño, mimos: dos minutos y está entera. **La evolución que se perdió debería reproducirse acá** (§9 R3). | 3 min |
| Mar 08:00–22:00 | | Etapa adulto: catorce horas de vistazos, dos visitas largas, siete días-mascota pasando por el reloj interno sin que nada los marque. **Este es el hueco.** | 5 × 40 s + 2 × 15 min |
| Mar 23:00 | | Apaga la luz. Segunda noche. | 60 s |
| Mié 07:01 | 1 d 12:00 | **→ Anciana.** Doce horas. Se enferma más, se recupera peor. Cada cuidado ya no construye nada. | 4 min |
| Mié 19:01 | 1 d 23:47 | **Muere de vejez.** Vivió 24 días-mascota. Memorial, última línea del cuaderno. | — |

Dos noches, dos días, dos sesiones ancla diarias y unos diez vistazos. Ese es el producto.

Los dos problemas que la tabla deja a la vista, y que el §9 ataca en ese orden: **las
evoluciones que ocurren sin público** y **las catorce horas de martes sin ninguna estructura**.

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

También justifica la mecánica de ausencia: el Vivario **sigue funcionando con la tapa cerrada**,
sólo que más despacio y con un regulador que no deja que la cosa se vaya al fondo. Eso es
literalmente `offlineDecayMultiplier` y `offlineHealthFloor`, y conviene que el juego lo diga
una vez, en voz de aparato, la primera vez que el jugador vuelve: *"el vivario la sostuvo."*

### 6.2 De dónde salen los huevos

Los huevos vienen del **Vivero**, un lugar del que el juego habla poco y nunca muestra. Nadie
sabe bien qué son estas criaturas ni de dónde salieron; se sabe que llegan en cápsulas, que
crecen si se las cuida y que no crecen dos veces igual. La ciencia del asunto es aburrida y no
importa. Lo que sí se dice: **el Vivero manda un huevo sólo cuando hay alguien dispuesto a
mirarlo.**

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
mucho y piden mucho, y una noche de descuido se les nota en el cuerpo. Cuernitos, brasa en el
pecho, andar impaciente. La familia que enseña disciplina a la fuerza.
*Su gesto característico:* la brasa del pecho se apaga a oscuras cuando tiene hambre.

**Leaf** — `hambre ×0,85 · energía ×0,85 · juego ×0,9`
Lentitud vegetal. Es la familia más resistente y la menos demostrativa: gasta poco, se entusiasma
poco, dura. Brote en la cabeza, movimientos de balanceo. Es la elección para el jugador que
quiere que el juego lo acompañe en vez de reclamarle, y la que mejor sostiene el preset "Slow"
de cuatro días.
*Su gesto característico:* el brote de la cabeza crece con la edad y florece de anciana.

**Volt** — `hambre ×1,1 · energía ×1,3 · juego ×1,25`
Puro nervio. Se queda sin energía un 30 % más rápido que nadie, duerme muchísimo, y cuando está
despierta convierte cualquier juego en fiesta (el mayor bono de felicidad por jugar del juego).
Orejas grandes, chispas, parpadeo rápido. La familia de las siestas y los estallidos.
*Su gesto característico:* se le eriza el pelo un segundo antes de despertarse.

### 6.5 El álbum y el cuaderno

Son las dos memorias del aparato y son **distintas a propósito**:

- **El álbum** es lo que *vos* elegiste guardar (fotos, `snapshot()`) más lo que el aparato
  archivó solo (cada evolución, con id `evo_`). Es visual y es de afuera: cómo se veía.
- **El cuaderno** (`Chronicle`, ya implementado, con su propia pantalla) es lo que *ella*
  escribió. Primera persona, sin fechas de calendario, sólo días-mascota. Es de adentro: cómo lo
  vivió. Se escribe desde los mismos eventos a los que reacciona la UI, así que **no puede
  contradecir lo que pasó de verdad** — esa restricción es lo que lo hace creíble.

La distancia entre los dos es donde vive el juego. El álbum puede tener una foto preciosa del
día 4; el cuaderno puede decir que el día 4 estuvo sola. Ninguno de los dos miente. Los dos
sobreviven a la criatura y pasan a la generación siguiente, y por eso los dos necesitan estar
**particionados por generación** (§8.6, §9 R5).

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
voz que dice "vivió veinticuatro días". No es poético. Su fuerza está en decir poco.

### 7.2 Reglas duras

- **La criatura no se refiere al jugador por rol.** Siempre "vos".
- **Nunca hay una barra de "te queda poco".** No hay cuentas regresivas hacia el castigo.
- **Nunca se usa el miedo a perder progreso.** Nada de "vas a perder tu racha", "última
  oportunidad".
- **Nunca hay urgencia falsa.** Si el estado no cambió, no hay notificación.
- **Nunca se cuantifica el afecto en la cara del jugador.** El vínculo puede ser un número
  interno; en pantalla es una postura.
- **El error se nombra, no se juzga.** "Estuvo con hambre" ✓. "La dejaste con hambre" ✗.
- **La ausencia se narra en pasado y sin adjetivos.** Es la superficie de texto más usada del
  juego ahora; una sola palabra de reproche ahí envenena el producto entero.
- **Nada de humor que rompa la ficción.** Sin memes, sin guiños al jugador, sin cuarta pared.
- **Todo texto es traducible.** Una cadena, un significado (hoy no se cumple, §8.8).

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
| 9 | *"Estuviste fuera 8 h 20 min. Durmió, se ensució dos veces y creció."* | Tarjeta de reencuentro. Hechos en pasado, sin adjetivos ni signos. El "y creció" es el gancho honesto. |
| 10 | *"Se fue. Cuando estés listo."* (`X is gone. Whenever you are ready.`) | Notificación de muerte, ya en el código. Informa sin convocar. Es el estándar de todo lo demás. |

### 7.4 Cinco líneas prohibidas

| # | Línea | Por qué está mal |
|---|---|---|
| 1 | *"¡Tu mascota se está muriendo! ¡Entrá ya!"* | Urgencia manufacturada + imperativo. Convierte el cuidado en pánico y el pánico en desinstalación. |
| 2 | *"¿Por qué me dejaste sola?"* | La criatura acusa. En el momento en que reprocha, deja de ser un ser vivo y se vuelve una palanca de retención. |
| 3 | *"¡Racha de 6 días! No la pierdas."* | Mecánica de pérdida disfrazada de logro. Premia la ansiedad, no el cariño. |
| 4 | *"Cuidado deficiente: 14 errores. Calificación: D."* | Le pone nota a la relación. El juego no es un examen. |
| 5 | *"Tu mascota murió. Abrí la app para empezar una nueva generación."* | Un duelo usado como llamada a la acción. **Ya se eliminó del código**, y queda acá como recordatorio de lo que no vuelve. |

### 7.5 Longitudes

| Superficie | Máximo | Tono |
|---|---|---|
| Toast | 40 caracteres | aparato |
| Burbuja de necesidad | 1 palabra | aparato |
| Notificación | 60 caracteres, sin signos de exclamación | aparato |
| Línea de cuaderno | 90 caracteres, una o dos oraciones | criatura |
| Tarjeta de reencuentro | 3 hechos, máx. 120 caracteres | aparato |
| Tarjeta de hito | 140 caracteres | narrador |
| Memorial | 3 líneas | narrador |

---

## 8. Qué se opone a la tesis

Registro completo, con estado. Los hallazgos resueltos se quedan escritos: saber qué estaba mal
y por qué se arregló vale tanto como la lista de pendientes.

### 8.1 La criatura no sobrevivía una noche · ✅ **RESUELTO**

**Qué pasaba.** Saciedad llena a 0 en 13–26 minutos; con la saciedad en 0 la salud caía
`0,055/s`. **Muerte entre 43 y 56 minutos de ausencia**, con todas las barras llenas. Ocho horas
de sueño humano mataban cualquier mascota sana, y el texto de Ajustes afirmaba lo contrario.

**Cómo se arregló.** `offlineDecayMultiplier = 0,30` y `offlineHealthFloor = 12`, aplicados
cuando el hueco supera 180 s; la protección se decide con el estado con que la dejaste, así que
la enfermedad sin curar sigue siendo mortal. `BalanceTest` fija las siete condiciones, incluida
la que importa: *"coming back after a night still finds a pet in trouble"*. La solución no
suavizó el juego, movió la culpa del lugar equivocado al correcto.

### 8.2 `Feral` es el resultado por defecto, y es un insulto · ⚠️ **PARCIAL — y agravado**

**Qué pasa.** `decideBranch()` evalúa `FERAL` primero: `neglect >= 6/hora || discipline < 15`.

**Lo que mejoró.** `DISCIPLINE_DRAIN` bajó de `0,004` a `0,0015` (de 14,4 a **5,4 puntos por
hora** despierta) y `praise()` subió de +2 a **+6**. `BalanceTest` fija que diez elogios bastan
para llegar a 60 de disciplina sin regañar nunca. Es un arreglo real.

**Lo que sigue mal.** La disciplina arranca en 20 y sigue decayendo sola: cruza el umbral de 15
en **≈ 55 minutos de vigilia**, y el primer `decideBranch()` recién ocurre a las **3 h 46 min**.
Un jugador que nunca descubre el botón "Felicitar" sigue obteniendo `FERAL` por omisión. Y el
§8.12 lo empeora muchísimo.

**Recomendación.** (a) Que la disciplina no decaiga sola, o que decaiga sólo por debajo de un
piso; (b) sacar `FERAL` del primer lugar del `when` y hacerlo requerir negligencia *sostenida y
observada*; (c) renombrar y redibujar la rama para que sea deseable — independiente, montaraz,
autosuficiente. Que alguien la quiera a propósito.

### 8.3 El regaño era la vía óptima, y la predicción era un spoiler · ✅ **RESUELTO en lo grueso**

**Qué pasaba.** `scold()` daba **+10** de disciplina contra los **+2** de `praise()`: la ruta
eficiente a `SCHOLAR` era retar seis veces seguidas. Y la pantalla de estadísticas mostraba
`Next form: Feral` en vivo desde el minuto 2, spoileando el clímax y empujando al regaño.

**Cómo se arregló.** `praise` +6 / `scold` +7, y `scold` ahora cuesta **2 de vínculo** (era 1).
El elogio pasó a ser la vía principal y el regaño un atajo caro. El spoiler se reemplazó por
`branchHint()`: la pantalla dice *"Leaning toward: wary, left to itself"* en vez de nombrar la
rama. Exactamente lo pedido.

**Lo que queda.** La disciplina sigue midiendo represión y elogio, no constancia. Un jugador que
alimenta puntualmente, apaga la luz de noche y cura en tres minutos no gana un solo punto de
disciplina por hacerlo bien. Eso es lo que falta (§9 R6).

### 8.4 El memorial le pone nota a un duelo · ❌ **ABIERTO**

**Qué pasa.** `MemorialScreen` sigue imprimiendo `"${pet.mealsEaten} meals · ${pet.gamesWon}
wins · ${pet.careMistakes} care mistakes"` y sigue poniendo **"Raise generation N+1"** como
botón primario con "Stay a moment" abajo. La lápida es idéntica para vejez y para inanición.

**Por qué ahora importa más.** Con dos días de inversión emocional en vez de cinco horas, el
memorial es la escena de mayor carga del producto y es la que menos trabajo tiene encima.

**Recomendación.** Sacar `careMistakes` de esta pantalla. Invertir la jerarquía de botones.
Cuatro tratamientos visuales según `DeathReason`. Agregar la última línea del cuaderno, que es
lo único que el jugador va a recordar.

### 8.5 La notificación de muerte era un anzuelo · ✅ **RESUELTO**

**Qué pasaba.** *"Your pet has passed away. Open the app to start a new generation."*
**Ahora dice:** *"X is gone. Whenever you are ready."* Informa sin convocar. Es el modelo.

### 8.6 El paso de generación pierde y mezcla memoria · ⚠️ **PARCIAL**

**Lo que mejoró.** `nextGeneration()` ahora hereda el `chronicle`. `Chronicle.trim()` descarta
días rutinarios antes que `MILESTONE` y `LOSS`; `CareActions.trimAlbum()` descarta selfies antes
que entradas `evo_`. `BalanceTest` fija las dos cosas. La eclosión de la generación 1 ya no se
puede borrar por escribir mucho.

**Lo que sigue mal.** Los topes (`MAX_ENTRIES = 120`, `ALBUM_LIMIT = 60`) son **globales, no por
generación**: en la generación 4 los hitos de las cuatro compiten por el mismo espacio, y como
los hitos son inmunes al recorte, el cuaderno termina siendo puros hitos sin ningún día común
entre ellos — una lista de partidas de nacimiento. Y `highScores` sigue sin heredarse.

**Recomendación.** Particionar cuaderno y álbum por generación, con tope por generación. Los
récords de minijuegos se heredan como "récord de la casa" con el nombre de quién lo hizo.

### 8.7 El álbum borraba el recuerdo más viejo en silencio · ✅ **RESUELTO**

`trimAlbum()` protege las entradas `evo_`. Queda pendiente sólo la partición (§8.6).

### 8.8 El juego habla en inglés desde el código · ❌ **ABIERTO**

`strings.xml` sigue teniendo 13 cadenas (nombres de botones). Todo el resto —toasts, rechazos,
cuaderno, memorial, tutorial, tienda, estadísticas— está escrito a mano en inglés dentro de los
`.kt`, concatenado con `${state.name}`. `FEATURES.md` 10.4 sigue afirmando que el contenido está
centralizado y traducido; no lo está. **Es prerequisito de todo el §7**: ninguna regla de voz se
puede aplicar a texto que vive esparcido en la lógica.

### 8.9 La calificación de cuidado insultaba a un recién nacido · ✅ **RESUELTO**

`careScore` ahora promedia sólo las cinco necesidades (saciedad, ánimo, energía, higiene, salud);
disciplina y vínculo quedaron fuera. Un recién nacido bien cuidado da **≈ 0,82 → grado "A"** en
vez de "C". El comentario en `Pet.kt` explica el porqué mejor que este documento.
*Queda una objeción menor:* seguir mostrando una nota con letra sobre una relación sigue siendo
un juicio de valor. Considerar reemplazarla por una palabra.

### 8.10 La personalidad se decide antes de que el jugador exista · ❌ **ABIERTO**

El comentario de `Personality` dice *"rolled from the first hours of care"*, pero `newGame()` la
sortea con `Random(seed)` en el instante de crear la partida. **Recomendación:** decidirla al
eclosionar, a partir de lo que hizo el jugador en los 90 segundos del huevo. Es barato, hace que
el huevo tenga sentido (§4.1) y convierte el primer minuto y medio en la primera decisión.

### 8.11 Los objetos prometen cosas que no hacen · ❌ **ABIERTO**

`toy_drum` dice "Unlocks Rhythm Tap" y `toy_cards` "Unlocks Memory Match", pero `GamesScreen`
sólo consulta `CareActions.canPlay()` y nunca mira el inventario. `toy_ball` promete "Ball
Rally", que no existe. **Recomendación:** o los juguetes desbloquean de verdad —y entonces son
un arco de progresión para la niñez de 3 horas, que buena falta le hace— o se reescriben las
descripciones.

### 8.12 Los errores de cuidado no distinguen ausencia de negligencia · 🔴 **NUEVO — CRÍTICO**

**Qué pasa.** `handleCareMistakes()` registra un error por cada minuto simulado en que la
saciedad está en ≤ 2, y **no está escalado por `decayScale`**. Durante una puesta al día de
8 horas, la saciedad llega a 0 alrededor de la primera hora y se queda ahí: el contador suma
**alrededor de 400 errores de cuidado por noche**.

`decideBranch()` calcula `neglect = careMistakes / horas`. Después de una sola noche, ese
cociente ronda **35 por hora** contra un umbral de 6. El segundo `decideBranch()` —el
definitivo, a las 11 h 46 min— cae justo después de esa noche.

**Por qué es crítico.** El arreglo del §8.1 le salvó el cuerpo a la mascota pero no la
historia: **todo jugador que duerma obtiene una adulta `Feral`, sin excepción y sin
explicación.** El juego ahora te deja dormir y después te dice que abandonaste a tu mascota.
Es el mismo error que teníamos, movido de la barra de salud al veredicto narrativo.

**Recomendación.** Multiplicar el registro por `decayScale`, o mejor: capar los errores de
cuidado durante la puesta al día a **uno por hora simulada**, y guardar por separado
"negligencia observada" (app abierta) de "deterioro por ausencia". El veredicto de rama sólo
debería mirar la primera. Un test de `BalanceTest` en la línea de los existentes:
*"una noche de sueño no puede convertir a nadie en Feral."*

### 8.13 La enfermedad sigue matando de noche, y ahora hay ocho veces más noches · 🔴 **NUEVO — CRÍTICO**

**Qué pasa.** El riesgo base de enfermarse es 0,4 % por chequeo cada 30 s ≈ **38 % por hora**.
La tasa fue afinada para una vida de 5 horas; ahora se aplica a una de 48. La mascota se enferma
más de una docena de veces por partida, y en la vejez (+2 % por chequeo) muchísimo más.

Y la protección offline **no aplica a la enfermedad**: `protectHealth` exige `!state.isSick`, y
además se evalúa con el estado inicial, así que si se enferma *durante* la ausencia la salud cae
sin piso a `0,055 × 0,6 × 0,30 = 0,0099/s` → **2 h 48 min desde salud llena**.

Cuenta redonda: en una ausencia de 8 horas, la probabilidad de enfermarse dentro de las primeras
5 h 12 min (o sea, con tiempo de sobra para morirse) es de **más del 80 %**.

**Por qué es crítico.** `BalanceTest.a healthy pet survives a full night away` pasa, pero pasa
con **una sola semilla determinista** (`rngSeed` fijo desde `newGame`). El test verifica un
camino, no la distribución. En manos de jugadores reales, la muerte nocturna volvió por la
puerta de al lado.

**Recomendación.** Tres cosas: (a) bajar el riesgo base para que escale con la longitud de la
vida, o ligarlo a `lifeSpeed`; (b) darle a la enfermedad contraída **durante** una ausencia el
mismo piso de salud que a todo lo demás — que te espere enferma, no muerta; (c) agregar tests
que corran muchas semillas y afirmen sobre la tasa, no sobre un caso.

*Matiz que hay que preservar:* dejarla enferma **a propósito** y desaparecer **sí** debe matar.
Esa es la única muerte por ausencia que el juego se ha ganado el derecho a tener.

### 8.14 Las evoluciones ocurren sin público y se sirven en un lote ilegible · 🟠 **NUEVO — ALTO**

**Qué pasa.** El envejecimiento **no** se ralentiza durante la ausencia (sólo las necesidades).
Con etapas de 8 y 24 horas, **la mayoría de las evoluciones de una partida ocurren con la app
cerrada**, incluido el `decideBranch()` definitivo del salto a Adulto (11 h 46 min).

Al reabrir, `handleEvents(offline = true)` dispara toda la noche de golpe: la animación de
evolución, el splash, los avisos de enfermedad, el de recuperación, los logros. Y
`showToast()` hace `toastJob?.cancel()` — **cada mensaje mata al anterior**. El jugador ve un
splash y un único toast superviviente, casi siempre el menos importante.

**Recomendación.** Una **cola de revelaciones**: durante la puesta al día, los eventos de tipo
hito no se reproducen, se encolan. Al abrir, la tarjeta de reencuentro los presenta en orden y
de a uno, con la escena de evolución reproducida entera y con el jugador mirando. Es el ítem
que más experiencia agrega por línea de código del roadmap entero.

### 8.15 El metabolismo no escala con `lifeSpeed` · 🟡 **NUEVO — MEDIO**

**Qué pasa.** `lifeSpeed` divide las duraciones de etapa pero las tasas de desgaste son
constantes absolutas. En "Demo" (4 h de vida) hacen falta ~12 comidas para criar a alguien; en
"Slow" (4 días) hacen falta ~250. La densidad de cuidado por vida varía en un factor de 20 entre
presets, y con ella cambia todo: el peso final (`GOURMAND` es casi imposible en Demo), la
cantidad de partidas jugadas (`ATHLETIC` pide 12), la exposición a enfermedad.

**Recomendación.** O bien escalar las tasas de desgaste por `lifeSpeed` (una vida rápida es una
vida acelerada, no una vida corta con metabolismo lento), o bien convertir los umbrales de
`decideBranch()` en fracciones de la vida esperada en vez de valores absolutos. La segunda es
más barata y más correcta.

### 8.16 Los guardias de intervalo disparan dos veces por ventana · 🟡 **NUEVO — MEDIO (bug)**

**Qué pasa.** `handleSickness()` usa `if (state.ageSeconds % SICK_CHECK_INTERVAL > dt) return`
y `handleCareMistakes()` usa `if (state.ageSeconds % 60L > dt) return`. Con `dt = 1` (el bucle
de primer plano corre un tick por segundo), la condición es falsa tanto para el resto `0` como
para el resto `1`: **el chequeo corre dos segundos consecutivos de cada ventana**.

En la práctica, el riesgo de enfermedad y la acumulación de errores de cuidado en primer plano
corren al **doble** de la tasa que las constantes declaran. Durante la puesta al día el paso es
más grueso y el error varía con el tamaño del hueco, así que la tasa efectiva además **cambia
según cuánto tiempo estuviste afuera** — lo que hace el balance imposible de razonar.

**Recomendación.** Cambiar la condición a `>= dt`, o mejor, llevar un acumulador explícito
(`secondsSinceLastSickCheck`) en el estado. Y rehacer los números de §2.4 después, porque hoy
la mitad de ellos describe la intención y no el comportamiento.

---

## 9. Roadmap narrativo

Priorizado por cuánto acerca el producto a la tesis por unidad de esfuerzo, **reordenado para el
ritmo de dos días**. **S** = días, **M** = una a dos semanas, **L** = más.

| # | Feature | Qué es | Por qué sirve a la tesis | Esf. |
|---|---|---|---|---|
| **R1** | **Errores de cuidado sensibles a la ausencia** | El registro de `careMistakes` se escala o se capa durante la puesta al día, y se separa "negligencia observada" de "deterioro por ausencia". Sólo la primera pesa en `decideBranch()`. | Hoy dormir una noche garantiza una adulta `Feral`. El juego te deja dormir y después te acusa de abandono: es la contradicción más grave que queda. | **S** |
| **R2** | **Enfermedad recalibrada + gracia offline** | Bajar el riesgo base a la escala de una vida de 48 h; darle a la enfermedad contraída *durante* una ausencia el mismo piso de salud que a todo lo demás. Tests sobre muchas semillas. | La muerte nocturna volvió por la puerta de al lado. Perder una mascota tiene que ser algo que hiciste. | **S** |
| **R3** | **Reencuentro con revelaciones en cola** | Tarjeta de reencuentro con 3–4 hechos en pasado, y los hitos ocurridos durante la ausencia (evolución, enfermedad, curación) reproducidos de a uno, con el jugador mirando. | Con etapas de 8 y 24 h la mayoría de las evoluciones pasan sin público, y el lote de eventos actual las pisa entre sí. Un momento que ocurrió sin nadie no ocurrió. | **M** |
| **R4** | **Memorial y despedida** | Cuatro tratamientos según causa de muerte, sin contador de errores, "quedarse" como acción primaria, última línea del cuaderno en pantalla, y el huevo siguiente **llega** al día siguiente en vez de salir de un menú. | Es la escena de mayor carga emocional del producto y la que menos trabajo tiene. Y quita el embudo del momento del duelo. | **S** |
| **R5** | **Cuaderno particionado + cartas de hito** | Topes por generación en `Chronicle` y en el álbum; y en cada evolución y en la vejez, la criatura escribe media página al cuidador, distinta según cómo se la crió. | El cuaderno ya existe y es lo mejor del repo. Falta que sobreviva al linaje y que tenga un momento largo, no sólo líneas sueltas. | **M** |
| **R6** | **Disciplina por constancia** | La disciplina deja de decaer sola y sube por cuidar a tiempo: comer antes de hambre crítica, luz apagada de noche, curar en < 3 min. `FERAL` sale del primer lugar del `when` y se rediseña como rama deseable. | Cierra lo que el rebalanceo dejó a medias: hoy la disciplina mide elogio y regaño, no presencia. | **M** |
| **R7** | **El día-mascota como unidad** | Con el reloj de 2 h, una vida tiene ~24 días-mascota. Cada uno recibe una forma: un amanecer, algo que la criatura hace ese día, una línea de cuaderno de cierre. | La etapa Adulto son 24 horas sin ninguna estructura: el hueco más grande del producto, y el rebalanceo lo multiplicó por cinco. Los doce días-mascota que caben adentro son doce capítulos esperando. | **M** |
| **R8** | **El huevo interactivo** | 90 segundos donde se puede tocar, abrigar y apagar la luz; esas acciones deciden la personalidad al eclosionar. Piso de duración para el preset Demo. | Hace que el primer minuto y medio sea una relación en vez de una sala de espera, y cumple lo que el código ya promete. | **S** |
| **R9** | **Vejez con mecánicas propias** | Doce horas donde el cuidado ya no construye: menos monedas y XP, la anciana pide compañía en vez de comida, cuenta cosas, se cansa. Reloj honesto de cuánto le queda. | El cuidado sin recompensa es el gesto más limpio del juego, y ahora dura media jornada. Hoy la vejez es "adulto con más riesgo de enfermarse". | **M** |
| **R10** | **Metabolismo ligado al ritmo** | Los umbrales de `decideBranch()` pasan a ser fracciones de `expectedLifetimeSeconds`, y/o las tasas de desgaste escalan con `lifeSpeed`. | Sin esto, Slow y Demo son juegos distintos con las mismas reglas, y las ramas son inalcanzables en uno e inevitables en el otro. | **S** |
| **R11** | **Memoria activa** | La criatura recuerda cómo la criaron y lo demuestra: se acerca al vidrio si el vínculo fue alto, duda si estuvo mucho sola, busca su juguete favorito. | "Recordar" en conducta pesa diez veces más que recordar en texto. Es la tesis hecha animación, y es lo que hace que valga la pena volver a un adulto sano. | **M** |
| **R12** | **Estaciones** | Cuatro estaciones ligadas a los días-mascota que repintan la sala, la ventana y el ánimo. Con 24 días por vida, cada estación dura ~6. | Da textura a las 36 horas de adultez y vejez, y hace que volver tenga novedad sin inventar tareas. Antes no cabía; ahora sí. | **M** |
| **R13** | **Carta de aniversario** | Cada 24 horas reales de vida, la criatura deja una nota corta sobre lo que va del camino. Dos por partida a ritmo Normal. | El rebalanceo creó una unidad nueva —el día real— y nada la marca. Es el gancho más barato para que el jugador vuelva al día siguiente sin que nadie le pida nada. | **S** |
| **R14** | **El objeto favorito** | En algún momento la criatura elige un objeto del inventario y lo adopta. Aparece en el cuarto, en las fotos, y se hereda a la generación siguiente. | Un detalle irracional y no optimizable es lo que hace que una criatura se sienta particular en vez de configurada. | **S** |
| **R15** | **Rechazos con carácter** | Cada personalidad rechaza distinto: Shy se esconde, Brave planta cara, Greedy come igual y se arrepiente. | El primer "no" es un latido (§4.2) y hoy es un toast genérico. Es la forma más barata de dar interioridad, y ahora ocurre en cada reencuentro. | **S** |
| **R16** | **Linaje** | Pantalla de árbol de generaciones: nombre, especie, rama, días vividos, causa, foto. Sin puntajes. Récords heredados como "récord de la casa". | Es el metajuego de la generación 3+. Sin esto, morir y volver a empezar es repetición; con esto, es historia. | **M** |
| **R17** | **Internacionalización real** | Todas las cadenas a recursos, con plurales y placeholders nombrados; español como idioma de primera clase. | Prerequisito duro del §7. No se puede dirigir la voz de un juego cuyo texto vive dentro de la lógica. | **M** |
| **R18** | **Modo sin barras** | Un ajuste que oculta todo el HUD numérico: sólo la criatura y la sala. | Prueba de fuego del arte y regalo para el jugador que ya entendió el juego. Si funciona, confirmamos la tesis; si no, sabemos qué arreglar. | **S** |
| **R19** | **Cápsula del tiempo** | Al morir de vejez, el cuidador guarda una cosa (una foto, una línea, el objeto favorito) que la próxima criatura encuentra en el cuarto y no entiende. | Cierra el círculo entre generaciones con un gesto de memoria, no de progresión. Es el mejor final que este juego puede tener. | **S** |
| **R20** | **Rutina aprendida** | La criatura aprende a qué hora sueles aparecer y empieza a esperarte a esa hora. Nunca reclama si no vas. | Con dos sesiones ancla por día durante dos días, hay patrón suficiente para detectar. Es lo más cerca que el juego puede estar de ser correspondido. | **L** |

**Explícitamente diferido:** las visitas entre mascotas (`FEATURES.md` 6.9, 7.7). Cualquier
ranking o comparación rompe la tesis en el acto; una foto compartida la reforzaría, pero no
antes de que R1–R7 estén hechos.

### Orden sugerido de ejecución

1. **Bloque de reparación del rebalanceo** (R1, R2, R3): el cambio de ritmo arregló la muerte
   por hambre y destapó tres cosas nuevas. Sin esto, la vida de dos días promete algo que no
   cumple.
2. **Bloque de cierre** (R4, R5): las dos escenas de mayor carga, memorial y cuaderno largo.
3. **Bloque de estructura** (R6, R7, R9, R10): le da forma a las 36 horas de adultez y vejez,
   que hoy son un desierto.
4. **Bloque de voz y carácter** (R17 primero, después R13, R14, R15, R8): R17 antes de contratar
   a nadie para escribir.
5. **Bloque de presencia y linaje** (R11, R12, R16, R18, R19).
6. **Bloque largo** (R20). Sólo cuando todo lo anterior esté firme.

---

## Apéndice — Números de referencia rápida

Para artistas y escritores que necesiten saber "cuánto dura esto". Todo a `lifeSpeed = 1.0`.

| Pregunta | Respuesta |
|---|---|
| ¿Cuánto vive una mascota? | **47 h 47 min de reloj real ≈ 2 días · ≈ 24 días-mascota** |
| ¿Y en los otros ritmos? | Slow 95 h 33 min (4 d) · Fast 15 h 55 min · Demo 3 h 59 min |
| ¿Cuánto dura un día-mascota? | 2 h reales (slider 5 min – 4 h). Sólo controla día/noche y el contador |
| ¿Cuándo es de noche? | de 21:30 a 6:30 del reloj interno = 45 min de cada 2 h |
| ¿Cuándo eclosiona? | 1 min 30 s |
| ¿Cuándo es la primera evolución? | **46 min 30 s** — dentro de la primera sesión |
| ¿Cuándo se decide la rama? | 3 h 46 min 30 s (Adolescente) y **de nuevo a las 11 h 46 min 30 s** (Adulto, definitivo) |
| ¿Cuándo llega la vejez? | 1 d 11 h 46 min · dura 12 h |
| ¿Cada cuánto hay que darle de comer? | **11–26 min con la app abierta · 35 min – 1 h 27 min en ausencia** |
| ¿Cada cuánto duerme? | siestas de ~4,7 min, cada 15–25 min en vivo |
| ¿Cada cuánto hace popó? | ~1 cada 14 min bien alimentada · tope 6 acumulados |
| ¿Cuánto aguanta sola? | **indefinidamente si está sana**: piso de salud 12. Vuelve famélica, sucia y de mal humor, pero viva |
| ¿Qué sí la mata en ausencia? | la enfermedad que dejaste sin curar (≈ 2 h 48 min desde salud llena) — y hoy demasiado (§8.13) |
| ¿A qué velocidad corre el tiempo estando afuera? | necesidades al **30 %** · edad, sueño y evolución al **100 %** |
| ¿Cuánto progreso offline se simula? | 12 h como máximo. Una semana afuera envejece 12 h |
| ¿Cada cuánto revisa el juego en segundo plano? | 15 min (mínimo de WorkManager) · 1 notificación como máximo · ninguna si está bien |
| ¿Cuántos objetos, logros y minijuegos hay? | 24 objetos · 24 logros · 3 minijuegos · 5 cuartos · 5 sombreros |
| ¿Cuánto guarda la memoria? | cuaderno 120 entradas · álbum 60 · **globales, no por generación** (§8.6) |
