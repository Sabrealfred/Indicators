# NeoPal — Lista completa de features

App Android de mascota virtual (tamagotchi) con estética de consola híbrida moderna:
chasis oscuro, dos rieles laterales neón (cian / rojo), pantalla con bisel y menú tipo grilla.

> **Nota legal:** el diseño es un *handheld genérico*. No se usa ninguna marca, logo, tipografía
> ni nombre de terceros (ni "Nintendo", ni "Switch", ni "Joy-Con"). Todo el arte es original y se
> dibuja por código.

Leyenda: **[✔]** implementado en este repo · **[○]** diseñado, pendiente de implementar.

---

## 1. Criatura y ciclo de vida

| # | Feature | Estado |
|---|---------|--------|
| 1.1 | 4 familias jugables (Aqua, Ember, Leaf, Volt) con sesgo propio de hambre, energía y juego | ✔ |
| 1.2 | 6 etapas: Huevo → Bebé → Niño → Adolescente → Adulto → Anciano | ✔ |
| 1.3 | Eclosión con temporizador, cascarón que se agrieta progresivamente y explosión de partículas | ✔ |
| 1.4 | 5 ramas evolutivas (Balanced, Athletic, Gourmand, Scholar, Feral) decididas por cómo lo criaste, sin azar | ✔ |
| 1.5 | 5 personalidades (Playful, Shy, Greedy, Brave, Calm) que alteran ritmos de decaimiento | ✔ |
| 1.6 | Peso corporal dinámico: engorda al comer, adelgaza al jugar, y cambia la silueta dibujada | ✔ |
| 1.7 | Muerte por inanición, enfermedad, negligencia o vejez, con pantalla memorial. Vida completa ≈ 2 días reales (ajustable: Slow/Normal/Fast/Demo) | ✔ |
| 1.8 | Generaciones: al morir, la siguiente hereda monedas, cosméticos, logros y álbum | ✔ |
| 1.9 | Cruce entre dos mascotas con herencia genética real (ver §11) | ✔ |
| 1.10 | Especies secretas desbloqueables por condiciones de crianza perfecta | ○ |
| 1.11 | La siguiente generación puede ser **una cría propia**: arranca del genoma de ese hijo y recuerda a sus padres | ✔ |

## 2. Simulación de necesidades

| # | Feature | Estado |
|---|---------|--------|
| 2.1 | 7 stats continuas 0–100: saciedad, felicidad, energía, higiene, salud, disciplina y vínculo | ✔ |
| 2.2 | Decaimiento en tiempo real, escalado por etapa, especie y personalidad | ✔ |
| 2.3 | Ciclo día/noche con reloj propio (día configurable, 2 h por defecto; solo afecta la visual y el contador) | ✔ |
| 2.4 | Sueño automático por cansancio o por apagar la luz; despertar por energía o amanecer | ✔ |
| 2.5 | Popó acumulable con moscas; ensucia el cuarto y sube el riesgo de enfermedad | ✔ |
| 2.6 | Enfermedad probabilística (suciedad, hambre, sobrepeso, vejez, salud baja) | ✔ |
| 2.7 | Registro de "errores de cuidado" (care mistakes) con ventana de perdón de 1 minuto | ✔ |
| 2.8 | Progreso offline al 30% de velocidad, con tope de 12 h: la ausencia sola nunca mata a una mascota sana | ✔ |
| 2.9 | Worker en segundo plano cada 15 min que avanza el mundo y decide si notificar | ✔ |
| 2.10 | Clima y estaciones que afectan el ánimo | ○ |

## 3. Interacciones de cuidado

| # | Feature | Estado |
|---|---------|--------|
| 3.1 | Alimentar con menú de despensa (comidas y golosinas, efectos distintos) | ✔ |
| 3.2 | Medicar; cura parcial o total según el ítem | ✔ |
| 3.3 | Limpiar el cuarto y bañar a la mascota | ✔ |
| 3.4 | Encender/apagar la luz y mandar a dormir | ✔ |
| 3.5 | Acariciar (tap directo sobre la criatura) | ✔ |
| 3.6 | Felicitar y regañar, con efecto opuesto sobre disciplina y felicidad | ✔ |
| 3.7 | Rechazos con reacción propia: llena, dormida, enojada, muerta | ✔ |
| 3.8 | Modo foto: mantener pulsada la mascota guarda una postal en el álbum | ✔ |
| 3.9 | Burbuja de pensamiento que muestra la necesidad más urgente | ✔ |
| 3.10 | Entrenamiento con mini-rutinas para subir stats específicas | ○ |

## 4. Arte y animación (todo vectorial, sin PNG)

| # | Feature | Estado |
|---|---------|--------|
| 4.1 | Criatura dibujada 100 % por código: cuerpo, panza, sombra, extremidades, cola, cresta | ✔ |
| 4.2 | Proporciones por etapa (bebé cabezón y ojón, anciano encogido) | ✔ |
| 4.3 | Rasgos por especie: aleta y branquias, cuernos de fuego, brote de hoja, orejas eléctricas | ✔ |
| 4.4 | Marcas por rama: lentes (Scholar), vincha (Athletic), servilleta (Gourmand), púas (Feral) | ✔ |
| 4.5 | Cara expresiva: párpados, cejas, pupilas con brillo y mirada errante, 5 bocas distintas | ✔ |
| 4.6 | Parpadeo automático, más frecuente cuando está nerviosa | ✔ |
| 4.7 | Squash & stretch, rebote, inclinación y balanceo de brazos en el idle | ✔ |
| 4.8 | 15 animaciones de reacción: comer, feliz, jugar, dormir, despertar, limpiar, curar, rechazar, regaño, elogio, evolución, eclosión, subir de nivel, muerte, idle | ✔ |
| 4.9 | Secuencia de evolución: estirón, fogonazo blanco y aparición de la nueva forma | ✔ |
| 4.10 | Sistema de partículas: corazones, destellos, migas, burbujas, Zzz, notas, estrellas, polvo, enojo, monedas | ✔ |
| 4.11 | 5 escenarios animados (Cozy, Beach, Space, Forest, Arcade) con parallax y props vivos | ✔ |
| 4.12 | Ventana con sol/luna en arco, estrellas titilantes y tinte nocturno progresivo | ✔ |
| 4.13 | Viñeta de sueño y aura verde pulsante de enfermedad | ✔ |
| 4.14 | Overlay de scanlines y bisel para vender el "pantalla dentro de consola" | ✔ |
| 4.15 | 5 sombreros cosméticos dibujados sobre la criatura | ✔ |
| 4.16 | Iconos de ítems vectoriales (20+), sin assets binarios | ✔ |
| 4.17 | Icono de app adaptativo (fondo con rieles neón + mascota) y fallback API 24-25 | ✔ |
| 4.18 | Modo "reduced motion" que baja la amplitud de toda la animación | ✔ |
| 4.19 | Skins/pieles alternas por color y export de la postal como PNG compartible | ○ |

## 5. Interfaz estilo consola

| # | Feature | Estado |
|---|---------|--------|
| 5.1 | Chasis con dos rieles laterales de color y pantalla con bisel redondeado | ✔ |
| 5.2 | Controles del riel funcionales: D-pad, sticks, botones A/B/X/Y, menú (–) y home (+) | ✔ |
| 5.3 | A = acariciar, X = jugar, Y = despensa, – = ajustes, + = estadísticas | ✔ |
| 5.4 | Pantalla de arranque animada con mascota, wordmark y "tap to start" | ✔ |
| 5.5 | Menú de juegos en grilla de "cartuchos" con miniaturas dibujadas | ✔ |
| 5.6 | Dock de acciones desplazable con badges (popó pendiente, enfermedad) | ✔ |
| 5.7 | Barras de stats animadas, con alerta roja bajo 25 y lectura para lectores de pantalla | ✔ |
| 5.8 | Toast deslizante, banner de logro y píldoras de monedas / nivel | ✔ |
| 5.9 | Transiciones de navegación laterales entre pantallas | ✔ |
| 5.10 | Tema claro/oscuro automático y edge-to-edge | ✔ |
| 5.11 | Soporte de gamepad físico y layout de tablet a dos columnas | ○ |

## 6. Progresión y economía

| # | Feature | Estado |
|---|---------|--------|
| 6.1 | Monedas por minijuegos, cuidados y logros | ✔ |
| 6.2 | XP y nivel de cuidador, con recompensa en monedas por nivel | ✔ |
| 6.3 | Tienda con 24 ítems en 5 pestañas (comida, cuidado, juguetes, sombreros, cuartos) | ✔ |
| 6.4 | Inventario con conteo, consumibles y cosméticos permanentes | ✔ |
| 6.5 | 24 logros con recompensa y pantalla de trofeos | ✔ |
| 6.6 | Álbum familiar: cada evolución se archiva sola + fotos manuales | ✔ |
| 6.7 | Pantalla de estadísticas completa con nota de cuidado (S/A/B/C/D/E) y predicción de forma | ✔ |
| 6.8 | Misiones diarias y racha de días cuidando | ○ |
| 6.9 | Ranking online y visitas entre mascotas de amigos | ○ |

## 7. Minijuegos

| # | Feature | Estado |
|---|---------|--------|
| 7.1 | **Rhythm Tap**: 4 carriles, notas que caen, ventana de acierto, combo y precisión | ✔ |
| 7.2 | **Memory Match**: secuencia creciente tipo llamada-respuesta, 6 rondas para ganar | ✔ |
| 7.3 | **Snack Catch**: la mascota es la paleta, se arrastra para atrapar comida y esquivar pastillas | ✔ |
| 7.4 | Recompensa unificada: puntaje normalizado 0–1 → monedas, XP, felicidad, vínculo y energía gastada | ✔ |
| 7.5 | Chrome común: barra de progreso, contadores y tarjeta de resultado | ✔ |
| 7.6 | Bloqueo de juego si está dormida, enferma, sin energía o es huevo | ✔ |
| 7.7 | Carreras y peleas amistosas entre mascotas | ○ |

## 8. Audio y háptica

| # | Feature | Estado |
|---|---------|--------|
| 8.1 | Motor chiptune propio: sintetiza ondas cuadradas/triangulares con AudioTrack, cero assets de audio | ✔ |
| 8.2 | 15 efectos: selección, confirmar, negar, comer, feliz, limpiar, curar, dormir, subir nivel, evolución, eclosión, moneda, acierto, fallo, muerte | ✔ |
| 8.3 | Envolvente de ataque/decaimiento para que los blips no suenen a click | ✔ |
| 8.4 | Vibración háptica en cada reacción, desactivable | ✔ |
| 8.5 | Música de fondo por escenario | ○ |

## 9. Persistencia y sistema

| # | Feature | Estado |
|---|---------|--------|
| 9.1 | Guardado con DataStore + JSON (kotlinx.serialization), tolerante a campos nuevos y viejos | ✔ |
| 9.2 | Guardado con debounce y guardado inmediato en momentos críticos | ✔ |
| 9.3 | Exportar/importar la partida como texto (portapapeles) | ✔ |
| 9.4 | Backup de Android incluido (`backup_rules` / `data_extraction_rules`) | ✔ |
| 9.5 | Notificaciones de cuidado con un solo mensaje prioritario, nunca spam | ✔ |
| 9.6 | Permiso de notificaciones POST_NOTIFICATIONS pedido en runtime (API 33+) | ✔ |
| 9.7 | Ajustes: sonido, háptica, notificaciones, movimiento reducido, velocidad del día, borrar partida | ✔ |
| 9.8 | Sincronización en la nube y multi-dispositivo | ○ |
| 9.9 | Widget de pantalla de inicio con el estado de la mascota | ○ |

## 10. Calidad

| # | Feature | Estado |
|---|---------|--------|
| 10.1 | Dominio 100 % puro (sin Android) → testeable con JUnit plano | ✔ |
| 10.2 | Tests de simulación: eclosión, decaimiento, tope offline, muerte, ramas, niveles, herencia | ✔ |
| 10.3 | Tests de acciones: alimentar, rechazo por saciedad, dormida, limpiar, compra sin monedas, recompensas | ✔ |
| 10.4 | Contenido textual centralizado en `strings.xml` con traducción ES | ✔ |
| 10.5 | Etiquetas de accesibilidad en controles y barras de stats | ✔ |
| 10.6 | Tests de UI con Compose y capturas de regresión visual | ○ |

---

## 11. La mitad autónoma

La mascota puede vivir su propio día: decidir, comer sola, estudiar, hacer amigos y tener crías.
Está apagado por defecto — es una pregunta sobre el jugador, no sobre la mascota.

| # | Feature | Estado |
|---|---------|--------|
| 11.1 | Tres niveles de autonomía: Manual, Asistido (sólo tareas propias) y Autónomo | ✔ |
| 11.2 | Cerebro de utilidad: puntúa cada opción contra necesidades, genes y hora, y **se compromete** con la elección por un rato | ✔ |
| 11.3 | Log de decisiones: qué eligió, por qué, con cuánta confianza y qué estuvo a punto de hacer en cambio | ✔ |
| 11.4 | La capacidad se separa del permiso: sin la skill, la mascota quiere y no puede — y eso se ve | ✔ |
| 11.5 | Comer solo consume inventario real; una comida propia vale exactamente lo mismo que una tuya | ✔ |
| 11.6 | Despensa vacía = no come, salvo que sepa `FORAGE` (más lento, alimenta menos, no cuenta como comida servida) | ✔ |
| 11.7 | Se acuesta sola de noche si aprendió a hacerlo, y nunca se despierta a sí misma | ✔ |
| 11.8 | 10 skills en escalera, cada una con su compuerta de intelecto y su tiempo de estudio | ✔ |
| 11.9 | Intelecto 0..100 que **satura**: acercarse a 100 cuesta cada vez más y no se alcanza desde abajo | ✔ |
| 11.10 | Lecciones del jugador: valen más que estudiar solo, y se limitan por la energía de la mascota, no por un cooldown | ✔ |
| 11.11 | Visitantes que llegan y se van, con nombres y genomas propios | ✔ |
| 11.12 | Afinidad que crece por trato y decae por ausencia, con piso por vínculo — un amigo no se evapora en una noche | ✔ |
| 11.13 | Cortejo y nido: huevo con genoma cruzado de ambos padres, con vista previa de la cría antes de decidir | ✔ |
| 11.14 | Bloqueo por consanguinidad y por otras ocho razones, cada una con su frase concreta | ✔ |
| 11.15 | Las crías heredan skills sólo si el padre aprendió `TEACH` | ✔ |

### Genética

| # | Feature | Estado |
|---|---------|--------|
| 11.16 | 14 genes continuos 0..1, heredados como un paquete: cuerpo y temperamento juntos | ✔ |
| 11.17 | Cruce por gen: hereda de un padre, del otro, o el punto medio, más mutación | ✔ |
| 11.18 | La silueta expresa el genoma: `stance` lleva de bípedo redondo a **cuadrúpedo**, más hocico, orejas y cola | ✔ |
| 11.19 | Un bebé casi no expresa su genoma: la forma llega a lo largo de las etapas | ✔ |
| 11.20 | El álbum guarda el genoma de cada foto, así que se ve cómo cambió la línea | ✔ |
| 11.21 | Vista lateral real de la cabeza en modo cuadrúpedo (hoy es una trampa de tres cuartos) | ○ |
| 11.22 | Pantallas de mente y colonia | ○ |

## Mapa de archivos

```
app/src/main/java/com/neopal/pet/
├── domain/            # Reglas del juego, puras y testeables
│   ├── Pet.kt         # Especies, etapas, ramas, stats, estado guardado, configuración
│   ├── Genetics.kt    # Genoma de 14 genes, cruce, y la morfología que expresa el cuerpo
│   ├── Mind.kt        # Vocabulario compartido: autonomía, actividad, decisión, skill, pal, huevo
│   ├── Brain.kt       # Decisión por utilidad: puntúa, elige y se compromete
│   ├── Colony.kt      # Visitantes, afinidad, cortejo, nido y crías
│   ├── Learning.kt    # Intelecto que satura, sesiones de estudio y escalera de skills
│   ├── Missions.kt    # Misiones diarias y racha de cuidado
│   ├── Chronicle.kt   # El diario, escrito desde los mismos eventos que ve la UI
│   ├── Items.kt       # Catálogo de 24 ítems
│   ├── Achievements.kt# 24 logros con su condición
│   ├── Simulation.kt  # Reloj, decaimiento, sueño, enfermedad, evolución, muerte, offline
│   └── CareActions.kt # Cada interacción del jugador (funciones puras)
├── data/PetRepository.kt   # DataStore + JSON, export/import
├── work/              # Worker periódico + notificaciones
├── audio/             # Sintetizador chiptune
└── ui/
    ├── art/           # Paletas, criatura, escenarios, partículas, iconos de ítems
    ├── components/    # Chasis de consola, escenario animado, widgets
    ├── screens/       # Boot, alta, home, stats, tienda, álbum, logros, ajustes, memorial
    ├── games/         # Rhythm Tap, Memory Match, Snack Catch
    ├── theme/         # Colores, tipografía, tema
    ├── PetViewModel.kt
    └── NeoPalNav.kt
```
