# NeoPal — plan de trabajo

Estado vivo de lo pedido, lo construido y lo que falta. Cada fila dice **quién puede hacerla en
paralelo** y **de qué depende**, porque el cuello de botella de este proyecto no es escribir código
sino que dos personas no toquen el mismo archivo.

Convenciones: **✅ hecho y verde en CI** · **🔨 en curso** · **📋 listo para empezar** · **💭 decisión pendiente**

---

## 0. Cómo se reparte el trabajo

Tres reglas, aprendidas rompiendo CI cuatro veces en esta rama:

1. **Propiedad de archivo estricta.** Un archivo tiene exactamente un dueño mientras se escribe.
   Los agentes en paralelo se coordinan por contrato, no por edición compartida.
2. **El contrato se fija antes de repartir.** Firmas, tipos y nombres se acuerdan primero; después
   cada frente codea contra ellos. Cambiar un contrato con gente adentro rompe trabajo en vuelo.
3. **Nada se commitea con `git add -A` mientras hay agentes escribiendo.** Rutas explícitas, y sólo
   archivos que llevan minutos sin cambiar. Dos veces arrastré archivos a medio escribir a la rama.

### Herramientas de verificación local

| Script | Qué prueba | Qué **no** prueba |
|---|---|---|
| `scratchpad/dtest.sh` | Compila el dominio y corre su suite | Nada de UI ni de red |
| `scratchpad/mindtest.sh` | Dominio + cliente remoto + sus tests | La ruta de red real |
| `scratchpad/uicheck.sh` | Frontend de todo el source set contra línea base | **No es un build**; sin SDK de Android |
| `scratchpad/xmlcheck.sh` | Que todo XML parsee | Semántica del manifiesto |

Límites conocidos de `uicheck.sh`, para que nadie lo lea como semáforo:
- No puede comparar un archivo **nuevo**: no hay entrada previa. Hay que comparar *clases de mensaje*
  contra las que producen archivos que CI ya compila verde.
- **Cambiar el classpath invalida la línea base.** Genera formas de cascada distintas y aparecen
  "errores nuevos" en archivos viejos. Regenerar la base con el mismo classpath.
- No ve XML, no ve recursos, no ve `R`. Un `R.string` inexistente pasa limpio y rompe CI.

---

## 1. El juego base

| # | Qué | Estado |
|---|---|---|
| 1.1 | 4 especies, 6 etapas, 5 ramas evolutivas, 5 personalidades | ✅ |
| 1.2 | Necesidades, enfermedad, sueño, 4 causas de muerte, progreso offline | ✅ |
| 1.3 | 24 ítems, 24 logros, monedas/XP/niveles | ✅ |
| 1.4 | Misiones diarias y racha de cuidado | ✅ |
| 1.5 | Diario en primera persona, álbum, memorial, registro de vidas | ✅ |
| 1.6 | Render pixel a factor entero, rampa de 32 tonos, noche por rotación de paleta | ✅ |
| 1.7 | Tres layouts reales (teléfono, landscape, tablet) | ✅ |

## 2. La mitad autónoma

| # | Qué | Estado |
|---|---|---|
| 2.1 | Tres niveles de autonomía: Manual / Asistido / Autónomo | ✅ |
| 2.2 | Cerebro de utilidad que se compromete con su elección | ✅ |
| 2.3 | Log de decisiones con el *por qué* en voz de la criatura | ✅ |
| 2.4 | Capacidad separada de permiso: sin skill quiere y no puede, y se ve | ✅ |
| 2.5 | Comer solo gastando inventario real | ✅ |
| 2.6 | 10 skills, intelecto que satura, lecciones del jugador | ✅ |
| 2.7 | Colonia: visitantes, afinidad, cortejo, nido, crías | ✅ |
| 2.8 | Genética de 14 genes; `stance` lleva a cuadrúpedo | ✅ |
| 2.9 | Lecciones de linaje, **sin necesidad de modelo** | ✅ |
| 2.10 | Pantallas de Mente y Colonia | ✅ |

## 3. El cerebro remoto

| # | Qué | Estado |
|---|---|---|
| 3.1 | Contrato puro: qué se le cuenta y qué se le permite decidir | ✅ |
| 3.2 | Cliente compatible con OpenRouter, sin dependencias nuevas | ✅ |
| 3.3 | Conversación con la criatura | ✅ |
| 3.4 | El modelo elige acción, revalidada al aplicarse | ✅ |
| 3.5 | Errands: mirar, planificar, ejecutar paso a paso | ✅ |
| 3.6 | Ajustes: encendido, clave propia, proxy, modelo, sub-toggles | ✅ |

### 3.7 Lo que **no** está y hay que decidir 💭

| # | Qué | Por qué está trabado |
|---|---|---|
| 3.7.1 | Proxy hospedado | Hay campo `proxyUrl` y funciona; falta levantar el servicio. Decisión tuya: Lambda, Cloudflare Worker, o un gateway de OpenClaw/Hermes |
| 3.7.2 | ¿Habla OpenClaw formato chat-completions? | **Sin verificar.** Su documentación no lo dice. Si sí, `proxyUrl` apunta ahí sin tocar código |
| 3.7.3 | Prosa en vez de JSON | El cliente exige JSON. Un modelo gratis que conteste en prosa queda descartado y el chat *parece* muerto. Sólo se sabe probando |

---

## 4. Hacerla más capaz — separando corrección de cautela

Esto responde a "no tengamos miedo de que se haga más inteligente". Las restricciones no son todas
iguales y conviene no tocarlas en bloque.

### 4.1 Restricciones de **corrección** — no se levantan

| Regla | Por qué se queda |
|---|---|
| El modelo elige **por índice** de una lista ya validada | Un modelo que nombra su propia acción eventualmente nombra una prohibida. No por mentir: por contestar sobre un mundo que cambió. Habría que revalidar cada guarda del simulador contra prosa |
| Cada paso se **revalida al ejecutarse** | Una respuesta viaja segundos por la red. El plato ya se comió, la caca ya se levantó, la criatura ya se durmió |
| Modo manual rechaza todo | Un jugador que tomó el volante lo conserva |
| Todo lo que vuelve del modelo se **acota y valida** | Es entrada hostil relayed por red, sin importar quién la generó |

### 4.2 Restricciones de **cautela y costo** — estas sí se levantan 📋

| # | Hoy | Propuesta | Depende de |
|---|---|---|---|
| 4.2.1 | ~~Plan de 3 pasos máximo~~ | ✅ **hecho** — 3 pasos al nacer, 6 con intelecto pleno, y un plan que va ganando se **extiende solo** hasta 3 veces. Sin modelo: la evidencia que le daría una lección es la que dice que vale seguir. El reloj pasó a ser **por paso** |
| 4.2.2 | Sólo herramientas de **lectura** | Herramientas que **actúan**, cada una por la misma revalidación que `adopt`. Más capaz sin debilitar nada | Contrato de tool-calling |
| 4.2.3 | ~~Cadencia fija~~ | ✅ **hecho** — la cadencia escala con el intelecto, en banda estrecha (0.6x–1.5x) para que un tier gratis dure el día |
| 4.2.4 | ~~Nunca aprende de sí misma~~ | ✅ **hecho** — cada plan se compara contra el cuidado con el que empezó; una mejora clara deja lección, topeada por debajo de lo que enseña una muerte |
| 4.2.5 | ~~Nunca inicia conversación~~ | ✅ **hecho** — habla sola sólo en primeras veces y puntos de quiebre (crecer, aprender una skill, hacer un amigo, emparejarse, una cría, curarse, sacar una lección propia). Nada de comidas: una criatura que comenta cada plato es una notificación |
| 4.2.6 | Un solo modelo para todo | Modelo chico para decidir, grande para conversar y planificar | `MindConfig` |

---

## 5. Juegos e interacciones 📋

### 5.1 Minijuegos

| # | Juego | Qué lo hace distinto | Paralelo |
|---|---|---|---|
| 5.1.1 | Rhythm Tap | ✅ existe | — |
| 5.1.2 | Memory Match | ✅ existe | — |
| 5.1.3 | Snack Catch | ✅ existe | — |
| 5.1.4 | ✅ **Escondidas** | Cooperativo *con* la criatura, no contra ella. Ella esconde o busca según su genoma | Archivo propio |
| 5.1.5 | ✅ **Traer la pelota** | Físicas simples; la criatura mejora con `vigor` y con práctica | Archivo propio |
| 5.1.6 | ✅ **Dueto** | Llamada y respuesta cantada; la criatura improvisa según personalidad | Archivo propio |
| 5.1.7 | ✅ **Rompecabezas de formas** | La criatura puede resolverlo **sola** si tiene intelecto suficiente — el primer juego donde mirarla jugar es el juego | Archivo propio |

Los cuatro se hicieron en paralelo, en cuatro archivos nuevos sin solapamiento, y **los cuatro
están cableados**: ruta en `NeoPalNav.kt`, cartucho en `GamesScreen.kt`. Siete juegos en la
estantería; la grilla ya reflowaba sola.

Lo que salió de hacerlos a la vez, para la próxima:
- **Las clases privadas de nivel superior SÍ chocan entre archivos del mismo paquete** (generan un
  `.class` por nombre); las `fun` y `const val` privadas no (van al facade del archivo). Un quinto
  juego que declare un `Phase` o un `Slot` pelado rompe el build. Prefijar por juego.
- El id de puntaje quedó pelado (`hide`, no `game_hide`) para no confundirlo con la ruta.

### 5.2 Interacciones táctiles

| # | Qué | Del backlog | Paralelo |
|---|---|---|---|
| 5.2.1 | ✅ Reacción por zona: cabeza feliz, panza risa, cola molestia | #23 | `PetStage.kt` |
| 5.2.2 | Pupilas que se dilatan al ver comida | #30 | `CreatureArt.kt` |
| 5.2.3 | ✅ Pellizcar para zoom (modo foto) | #40 | `PetStage.kt` |
| 5.2.4 | Sacudir el teléfono para despertarla | #41 | Sensor nuevo |
| 5.2.5 | Soplar al micrófono para las velas | #42 | Permiso de micrófono 💭 |
| 5.2.6 | Envejecimiento visual gradual dentro de cada etapa | #31 | `CreatureArt.kt` |

⚠️ 5.2.2 y 5.2.6 siguen abiertos y **ambos tocan `CreatureArt.kt`**: un solo frente, en serie.

Las zonas se calculan de la geometría dibujada (posición, radio de la etapa, `Morphology.bodyWidth`,
genes de postura y cola), no de cajas en pantalla: una criatura sin cola no tiene zona de cola.
La cola **no paga nada** — el castigo por tironearla es la caricia que no cobraste. Quedó un
parámetro `onTugTail` con default vacío por si algún día se quiere que cueste; hoy nadie lo pasa.

### 5.3 Modos de render pendientes

| # | Qué | Del backlog |
|---|---|---|
| 5.3.1 | ✅ Modo CRT: scanlines curvas, viñeta, sangrado | #8 |
| 5.3.2 | ✅ Modo LCD verde de 1997, 4 tonos | #9 |
| 5.3.3 | Transición animada al cambiar de modo | #10 |

5.3.3 (transición animada al cambiar de modo) sigue abierto y vive en `PixelRenderer.kt`.

El enum se mudó al dominio porque es una preferencia **guardada**. Y el modo que reemplaza el
color se queda con todo el acabado: atmósfera, luces apagadas y viñeta de sueño se saltean, porque
corren *después* del blit a resolución de pantalla y le meterían un quinto y un sexto tono a una
pantalla que existe para tener cuatro. La noche pasa a ser **exposición**, no oscuridad — una
pantalla de cuatro tonos no tiene más oscuro adonde ir.

---

## 6. Deuda conocida

| # | Qué | Por qué sigue ahí |
|---|---|---|
| 6.1 | ~170 textos hardcodeados en inglés | Mover requiere decidir cómo entra `Context` a un dominio hoy puro, que es lo que lo hace testeable |
| 6.2 | Sin tests de UI ni regresión visual | Es exactamente el agujero por donde se coló el pixelado feo. **Se agrandó**: entraron ~5.000 líneas de UI nueva y nadie vio un solo píxel de ninguna |
| 6.7 | Nada de lo nuevo se corrió ni se escuchó | Los cuatro juegos, las dos pantallas retro y las zonas táctiles están verificados por aritmética y por simulación en JVM, nunca por una pantalla. El dueto en particular: **no se escuchó una sola nota** |
| 6.8 | `onTugTail` es un parámetro que nadie pasa | Costura deliberada por si tironear la cola debe costar algo. Hoy el castigo es la caricia no cobrada, que alcanza |
| 6.3 | Cabeza del cuadrúpedo de frente sobre cuerpo de perfil | Arreglarlo pide una cabeza lateral aparte |
| 6.4 | `stance` 0.30–0.55 se lee como un ocho | El rango menos lindo del barrido |
| 6.5 | Log de decisiones no es lazy (hasta 40 filas) | Acotado hoy; si el tope crece, `LazyColumn` |
| 6.6 | Ruta de red nunca ejecutada | Sin SDK acá; `post()`, timeouts y cancelación son razonados, no corridos |

### El bug que 6.6 dejó pasar — anotado porque la forma se repite

Los tres throttles del cerebro remoto arrancaban en `Long.MIN_VALUE` y leían
`pet.ageSeconds - lastX < gap`. Esa resta se desborda para cualquier edad y vuelve a un negativo
grande, así que el gap nunca se cumplía: `maybeReconsider` y `maybePlan` **jamás se llamaron**.

Lo que lo hace digno de anotar no es la aritmética sino que **no se ve desde afuera**. No hay
crash, no hay log, ajustes reporta una ruta, el cliente es alcanzable — y el cerebro local cubre
cada llamada omitida perfectamente. El único síntoma es una criatura que decide todo sola, que es
exactamente como se ve la función apagada.

Misma forma que el `0L` de `Errands.sanitise`: **un fallo silencioso que el camino local disimula**.
Cada vez que lo remoto es un extra sobre algo que ya funciona, apagarlo por accidente no se nota.
Regla que sale de acá: todo lo que decida *no* llamar a la red merece o un test o un contador
visible, porque su falla se parece demasiado a su éxito.

---

## 7. Orden sugerido

La tanda de seis frentes en paralelo (§5.1 ×4, §5.2 en `PetStage`, §5.3 en `PixelRenderer`) está
cerrada y verde en CI. Lo que queda:

**Ahora, en paralelo (dos frentes sin solapamiento):**
1. Arte en `CreatureArt.kt` (§5.2.2 pupilas, §5.2.6 envejecimiento) — **un solo frente**, los dos
   items tocan el mismo archivo
2. Transición al cambiar de modo de pantalla (§5.3.3) en `PixelRenderer.kt`

**Después, en serie (tocan el mismo contrato):**
3. Herramientas que actúan (§4.2.2) — cambia el contrato del proveedor, no se paraleliza
4. Un modelo por rol (§4.2.6) — `MindConfig`, y toca el cliente

**Bloqueado por decisión tuya:**
- El proxy (§3.7.1) y si OpenClaw sirve como gateway (§3.7.2)
- Permiso de micrófono (§5.2.5), sacudir el teléfono (§5.2.4) pide un sensor nuevo
