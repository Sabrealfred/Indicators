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
| 4.2.1 | Plan de 3 pasos máximo | Planes largos que se **extienden solos** al terminar, si la criatura tiene intelecto alto | `Errands.MAX_STEPS`, sub-planes |
| 4.2.2 | Sólo herramientas de **lectura** | Herramientas que **actúan**, cada una por la misma revalidación que `adopt`. Más capaz sin debilitar nada | Contrato de tool-calling |
| 4.2.3 | Reconsidera cada 5 min, planifica cada 30 | Cadencia por intelecto: una criatura brillante piensa más seguido | Configurable en Ajustes |
| 4.2.4 | Nunca aprende de sus propios planes | **Evaluar el resultado**: ¿el plan sirvió? Lecciones desde su propia experiencia, no sólo desde la muerte del padre | `Lineage` + `Errands` |
| 4.2.5 | Nunca inicia conversación | Que hable sola cuando pasa algo que le importa | `TalkScreen` + notificaciones |
| 4.2.6 | Un solo modelo para todo | Modelo chico para decidir, grande para conversar y planificar | `MindConfig` |

---

## 5. Juegos e interacciones 📋

### 5.1 Minijuegos

| # | Juego | Qué lo hace distinto | Paralelo |
|---|---|---|---|
| 5.1.1 | Rhythm Tap | ✅ existe | — |
| 5.1.2 | Memory Match | ✅ existe | — |
| 5.1.3 | Snack Catch | ✅ existe | — |
| 5.1.4 | **Escondidas** | Cooperativo *con* la criatura, no contra ella. Ella esconde o busca según su genoma | Archivo propio |
| 5.1.5 | **Traer la pelota** | Físicas simples; la criatura mejora con `vigor` y con práctica | Archivo propio |
| 5.1.6 | **Dueto** | Llamada y respuesta cantada; la criatura improvisa según personalidad | Archivo propio |
| 5.1.7 | **Rompecabezas de formas** | La criatura puede resolverlo **sola** si tiene intelecto suficiente — el primer juego donde mirarla jugar es el juego | Archivo propio |

Los cuatro son archivos nuevos e independientes: **se pueden hacer los cuatro en paralelo**.
Contrato compartido: cada uno expone `@Composable fun XGame(viewModel, onExit)` y termina llamando
`viewModel.finishGame(won, score, gameName, gameId, points)`.

### 5.2 Interacciones táctiles

| # | Qué | Del backlog | Paralelo |
|---|---|---|---|
| 5.2.1 | Reacción por zona: cabeza feliz, panza risa, cola molestia | #23 | `PetStage.kt` |
| 5.2.2 | Pupilas que se dilatan al ver comida | #30 | `CreatureArt.kt` |
| 5.2.3 | Pellizcar para zoom (modo foto) | #40 | `PetStage.kt` |
| 5.2.4 | Sacudir el teléfono para despertarla | #41 | Sensor nuevo |
| 5.2.5 | Soplar al micrófono para las velas | #42 | Permiso de micrófono 💭 |
| 5.2.6 | Envejecimiento visual gradual dentro de cada etapa | #31 | `CreatureArt.kt` |

⚠️ 5.2.1, 5.2.3 tocan `PetStage.kt`; 5.2.2, 5.2.6 tocan `CreatureArt.kt`. **Dos frentes, no seis.**

### 5.3 Modos de render pendientes

| # | Qué | Del backlog |
|---|---|---|
| 5.3.1 | Modo CRT: scanlines curvas, viñeta, sangrado | #8 |
| 5.3.2 | Modo LCD verde de 1997, 4 tonos | #9 |
| 5.3.3 | Transición animada al cambiar de modo | #10 |

Los tres viven en `PixelRenderer.kt`: **un solo frente**.

---

## 6. Deuda conocida

| # | Qué | Por qué sigue ahí |
|---|---|---|
| 6.1 | ~170 textos hardcodeados en inglés | Mover requiere decidir cómo entra `Context` a un dominio hoy puro, que es lo que lo hace testeable |
| 6.2 | Sin tests de UI ni regresión visual | Es exactamente el agujero por donde se coló el pixelado feo |
| 6.3 | Cabeza del cuadrúpedo de frente sobre cuerpo de perfil | Arreglarlo pide una cabeza lateral aparte |
| 6.4 | `stance` 0.30–0.55 se lee como un ocho | El rango menos lindo del barrido |
| 6.5 | Log de decisiones no es lazy (hasta 40 filas) | Acotado hoy; si el tope crece, `LazyColumn` |
| 6.6 | Ruta de red nunca ejecutada | Sin SDK acá; `post()`, timeouts y cancelación son razonados, no corridos |

---

## 7. Orden sugerido

**Ahora, en paralelo (cuatro frentes sin solapamiento):**
1. Los cuatro minijuegos nuevos (§5.1) — cuatro archivos nuevos, cero conflicto
2. Interacciones en `PetStage.kt` (§5.2.1, §5.2.3)
3. Arte en `CreatureArt.kt` (§5.2.2, §5.2.6)
4. Modos de render en `PixelRenderer.kt` (§5.3)

**Después, en serie (tocan el mismo contrato):**
5. Herramientas que actúan (§4.2.2) — cambia el contrato del proveedor
6. Aprender de los propios planes (§4.2.4) — cruza `Errands` y `Lineage`
7. Cadencia por intelecto (§4.2.3)

**Bloqueado por decisión tuya:**
- El proxy (§3.7.1) y si OpenClaw sirve como gateway (§3.7.2)
- Permiso de micrófono (§5.2.5)
