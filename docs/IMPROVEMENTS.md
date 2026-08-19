# 100 mejoras para NeoPal

Estado: **✅ hecho en esta tanda** · **🔜 siguiente** · **💡 idea a evaluar**

---

## A. Píxeles y render (1–12)

| # | Mejora | Estado |
|---|--------|--------|
| 1 | Render a buffer de baja resolución y escalado nearest-neighbour: píxeles reales, no vectores suaves | ✅ |
| 2 | Resolución de píxel configurable (96 / 144 / 216 / 288 px de alto) | ✅ |
| 3 | Interruptor de modo píxel en ajustes, con vista previa en vivo | ✅ |
| 4 | Paleta limitada por escena (dithering de 16–32 colores) para que el look sea consistente | 🔜 |
| 5 | Dithering de Bayer en los degradados de cielo en vez de gradiente continuo | 🔜 |
| 6 | Snap de posiciones al grid de píxel para que la criatura no "flote" entre subpíxeles | 🔜 |
| 7 | Outline de 1 px consistente en todo sprite (hoy el grosor varía con el tamaño) | 🔜 |
| 8 | Modo CRT opcional: scanlines curvas, viñeta y sangrado de color | 💡 |
| 9 | Modo "LCD verde" retro: 4 tonos verdes, como un tamagotchi de 1997 | 💡 |
| 10 | Transición animada al cambiar de modo (el mundo se pixela progresivamente) | 💡 |
| 11 | Cache de sprites: rasterizar cada pose una vez en vez de redibujar paths cada frame | 🔜 |
| 12 | Medidor de FPS oculto en ajustes de desarrollador | 💡 |

## B. La criatura: animación y expresión (13–32)

| # | Mejora | Estado |
|---|--------|--------|
| 13 | Salto real con anticipación, elevación y aterrizaje con squash (hoy es un bob senoidal) | 🔜 |
| 14 | Caminar por el cuarto solo: la mascota deambula y se detiene en props | 🔜 |
| 15 | Mirada que sigue tu dedo mientras acariciás | ✅ |
| 16 | Parpadeo doble ocasional y parpadeo más rápido cuando está nerviosa | ✅ (parcial: falta el doble) |
| 17 | Cambio de color según el ánimo: verde enfermo, rojizo enojado, pálido con hambre | ✅ (enfermo) 🔜 (resto) |
| 18 | Rubor progresivo cuando el vínculo sube, no un estado binario | 🔜 |
| 19 | Respiración visible distinta despierta/dormida/enferma | ✅ |
| 20 | Cola con física de resorte que sigue al cuerpo con retardo | 🔜 |
| 21 | Sombra que se achica y aclara cuando salta | 🔜 |
| 22 | Poses idle secundarias: bostezo, rascarse, mirar al techo, sentarse | 🔜 |
| 23 | Reacción al toque por zona: cabeza = feliz, panza = risa, cola = molestia | 💡 |
| 24 | Estirarse al despertar antes de volver al idle | 🔜 |
| 25 | Animación de comer con la comida visible en pantalla, no solo migas | 🔜 |
| 26 | Transición de evolución con silueta a contraluz antes del fogonazo | ✅ (fogonazo) 🔜 (silueta) |
| 27 | Anillo de "aura" según la rama evolutiva (Athletic deja estela al moverse) | 💡 |
| 28 | Micro-expresiones: la ceja se mueve al tocar la pantalla aunque no haya acción | 🔜 |
| 29 | Sudor cuando la energía está baja y tirita cuando está enferma | 🔜 |
| 30 | Pupilas que se dilatan al ver comida | 💡 |
| 31 | Envejecimiento visual gradual dentro de cada etapa, no solo al cambiar de etapa | 💡 |
| 32 | Accesorios que se mueven con el cuerpo (el gorro rebota al saltar) | 🔜 |

## C. Interacciones táctiles (33–48)

| # | Mejora | Estado |
|---|--------|--------|
| 33 | Doble toque para hacerle cosquillas | ✅ |
| 34 | Deslizar hacia arriba para lanzarla al aire | ✅ |
| 35 | Acariciar arrastrando el dedo, con estela de corazones | ✅ |
| 36 | Tocar una caca para recogerla de a una, en vez de un botón de menú | ✅ |
| 37 | Mantener pulsado para sacar una foto | ✅ |
| 38 | Arrastrar la comida desde la despensa hasta la boca | 🔜 |
| 39 | Arrastrar el jabón sobre el cuerpo para bañarla, frotando | 🔜 |
| 40 | Pellizcar para hacer zoom en la mascota (modo foto) | 💡 |
| 41 | Sacudir el teléfono para despertarla (acelerómetro) | 💡 |
| 42 | Soplar al micrófono para apagar las velas del cumpleaños | 💡 |
| 43 | Números flotantes "+12 MOOD" en cada acción | ✅ |
| 44 | Vibración diferenciada por peso de la acción | ✅ |
| 45 | Rechazos con lenguaje corporal, no solo un cartel | ✅ |
| 46 | Deshacer inmediato tras una acción equivocada (5 s) | 💡 |
| 47 | Modo zurdo: dock y rieles espejados | 🔜 |
| 48 | Atajo de "cuidado rápido": una acción resuelve la necesidad más urgente | 🔜 |

## D. UI, pantallas y flujo (49–64)

| # | Mejora | Estado |
|---|--------|--------|
| 49 | Tutorial de primera vez con las gestos invisibles explicados | ✅ |
| 50 | Repetir el tutorial desde ajustes | ✅ |
| 51 | Botones que se hunden al presionar, con rebote de resorte | ✅ |
| 52 | Badge urgente que late para llevar el ojo sin cambiar de color | ✅ |
| 53 | Sacudida de pantalla en evolución, nivel y muerte | ✅ |
| 54 | Pantalla de "mientras no estabas": resumen de lo que pasó offline | 🔜 |
| 55 | Widget de pantalla de inicio con el estado y una acción rápida | 🔜 |
| 56 | Diario de la mascota escrito en primera persona, con línea de tiempo | ✅ |
| 57 | Récords por minijuego visibles en el menú de juegos | ✅ |
| 58 | Comparativa de generaciones (esta vs. la anterior) | 🔜 |
| 59 | Pantalla de detalle de ítem antes de comprar (qué sube, qué baja) | 🔜 |
| 60 | Búsqueda y filtros en la tienda cuando haya más de 40 ítems | 💡 |
| 61 | Modo una mano: dock más abajo, alcanzable con el pulgar | 🔜 |
| 62 | Estados vacíos con personalidad (álbum vacío, tienda sin monedas) | 🔜 |
| 63 | Transiciones compartidas entre la mascota del home y la del álbum | 💡 |
| 64 | Modo horizontal y layout de tablet a dos columnas | 🔜 |

## E. Iconos, tipografía y color (65–74)

| # | Mejora | Estado |
|---|--------|--------|
| 65 | Sombra de contacto, contorno y brillo consistentes en todos los iconos | ✅ |
| 66 | Icono de cuarto distinto por tema, no genérico | ✅ |
| 67 | Barrido de brillo animado en ítems nuevos o destacados | ✅ (API lista) 🔜 (usarlo) |
| 68 | Siluetas inconfundibles: ningún par de iconos se confunde a 24 px | ✅ |
| 69 | Fuente bitmap propia para los números del HUD | 🔜 |
| 70 | Paleta accesible verificada para daltonismo en las barras de stats | 🔜 |
| 71 | Iconos animados en el dock (la comida humea, el jabón burbujea) | 💡 |
| 72 | Set de iconos alternativo "line art" como opción | 💡 |
| 73 | Color de acento configurable por el jugador | 💡 |
| 74 | Icono de app que cambia según la etapa de vida | 💡 |

## F. Escenarios y ambiente (75–82)

| # | Mejora | Estado |
|---|--------|--------|
| 75 | Ciclo día/noche continuo con amanecer y atardecer teñidos | ✅ |
| 76 | Movimiento ambiental por tema: nubes, olas, cometa, hojas, marquesina | ✅ |
| 77 | Capa de primer plano con parallax invertido para dar profundidad | ✅ |
| 78 | Sistema de clima (lluvia, nieve) disponible para eventos | ✅ (API) 🔜 (integrarlo) |
| 79 | Estaciones que cambian la decoración cada N días de mascota | 🔜 |
| 80 | Cacas con variación de tamaño y rotación por índice | ✅ |
| 81 | Objetos del cuarto interactuables (tocar la lámpara la enciende) | 🔜 |
| 82 | Editor de cuarto: mover y colocar decoraciones | 💡 |

## G. Minijuegos (83–90)

| # | Mejora | Estado |
|---|--------|--------|
| 83 | Cuenta regresiva 3-2-1-GO antes de empezar | ✅ |
| 84 | Juicio de precisión PERFECT/GOOD/MISS con combo | ✅ |
| 85 | Tono que sube con la racha, para oír el combo | ✅ |
| 86 | Memoria como melodía: cada pad tiene su nota | ✅ |
| 87 | Multiplicador de racha, ítem dorado y vidas dibujadas como corazones | ✅ |
| 88 | Cuarto juego cooperativo con la mascota (esconder y buscar) | 💡 |
| 89 | Dificultad adaptativa según el historial del jugador | 💡 |
| 90 | Repetición de la mejor partida en el álbum | 💡 |

## H. Audio (91–94)

| # | Mejora | Estado |
|---|--------|--------|
| 91 | Volumen maestro y desplazamiento de tono en el sintetizador | ✅ |
| 92 | Música de fondo procedural por escenario y hora del día | 🔜 |
| 93 | Voz de la mascota: balbuceos sintetizados distintos por especie | 🔜 |
| 94 | Ducking del audio cuando entra una notificación del sistema | 💡 |

## I bis. Balance de vida (hallazgos verificados y corregidos)

Un agente de diseño auditó el simulador contra el código y encontró tres cosas que rompían el juego:

| Hallazgo | Antes | Ahora |
|---|---|---|
| **Dormir mataba a la mascota** | Con las tasas reales, una mascota llena moría en **50 minutos** de ausencia; 8 h de sueño humano la mataban siempre | El tiempo ausente corre al 30% y la ausencia por sí sola nunca baja la salud del piso; solo una enfermedad que dejaste sin tratar sigue siendo mortal |
| **La vida entera duraba 5 horas** | 15 días-mascota × 20 min = una vida completa mientras dormías | Etapas en tiempo real con curva adelantada: primera evolución a los 45 min, vida completa ≈ 2 días reales, ajustable (Slow/Normal/Fast/Demo) |
| **Feral era el destino por defecto** | La disciplina caía 14,4 puntos/hora y regañar daba +10 contra +2 de felicitar: el único camino a Scholar era retar a la criatura | Disciplina cae 5,4/hora, felicitar da +6 y regañar +7 con más costo de vínculo: la constancia amable es una vía real |
| El diario y el álbum borraban lo importante | `takeLast` se comía la eclosión y las evoluciones | La retención descarta días rutinarios y selfies primero; hitos y evoluciones no se borran |
| La pantalla de stats spoileaba la evolución exacta | "Next form: Feral" | "Leaning toward: wary, left to itself" |
| La notificación de muerte era un anzuelo | "Open the app to start a new generation" | "X is gone. Whenever you are ready." |
| Un recién nacido perfecto sacaba nota C | `careScore` promediaba disciplina y vínculo, que empiezan bajos a propósito | Solo cuentan las cinco necesidades |

## I. Progresión, narrativa y sistema (95–100)

| # | Mejora | Estado |
|---|--------|--------|
| 95 | El diario se hereda entre generaciones: un libro familiar | ✅ |
| 96 | Cartas de hito ("tu mascota cumplió 5 días") como recuerdo coleccionable | 🔜 |
| 97 | Misiones diarias y racha de días cuidando | 🔜 |
| 98 | Resumen de vida al morir, compartible como imagen | 🔜 |
| 99 | Sincronización en la nube y traspaso entre dispositivos | 💡 |
| 100 | Tests de UI con capturas de regresión visual para el arte procedural | 🔜 |

---

## Lo que se implementó en esta tanda

Render en píxeles reales con resolución configurable · números flotantes de stats · sacudida de pantalla ·
botones con hundido y badge que late · cosquillas, lanzamiento, caricia con arrastre y recogida de cacas por toque ·
tutorial de primera vez · diario de la mascota heredable con línea de tiempo · récords por minijuego ·
cuenta regresiva, juicios de precisión, melodía en memoria, racha e ítem dorado en atrapar ·
volumen y tono en el audio · amanecer/atardecer, clima y parallax de primer plano · iconos con sombra, contorno y brillo.
