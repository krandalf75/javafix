# Plan: JavaFix como skill de IA instalable

## Objetivo

Permitir que un agente utilice JavaFix desde cualquier proyecto Java para:

1. Validar, antes del commit/push, los `.java` staged, unstaged y nuevos del worktree.
2. Analizar todo el proyecto cuando lo solicite el usuario.
3. Evaluar los diagnósticos y revisar la corrección de cada cambio propuesto antes
   de escribir código.
4. Aplicar reparaciones solo cuando el usuario lo solicite o apruebe la vista previa.

## Estado y decisión de integración

JavaFix ya expone `analyze --project ... --files ...`, análisis sin `--files`,
salida JSON no interactiva y `repair` con vista previa por defecto. También existe
una skill de OpenCode en `.opencode/skills/javafix/`. Por tanto, la primera entrega
debe ser una skill bien empaquetada y documentada, no un plugin.

Una skill es la opción más portable y segura para este flujo: describe cómo
invocar la CLI y obliga al agente a razonar sobre los resultados, mientras que un
plugin solo es necesario si se necesitan herramientas de interfaz o integraciones
específicas de un agente. No implementar un plugin ahora; revisar esta decisión
si se solicita una instalación con un clic o compatibilidad con otros clientes.

## Alcance de la implementación

### Fase 1 — Skill segura y práctica

- [x] Documentar la selección de archivos modificados con Git: cambios preparados y
  sin preparar respecto a `HEAD`, además de archivos `.java` no rastreados; ignorar
  los archivos eliminados y deduplicar los resultados.
- [x] Permitir el análisis completo sin `--files` solo cuando lo solicite el
  usuario; no convertir implícitamente una lista de cambios vacía en un análisis
  completo.
- [x] Hacer que el agente compruebe `status` y `analysisComplete`; un resultado
  `incomplete` no demuestra que no existan los problemas que faltan.
- [x] Exigir que el agente contraste cada hallazgo con el código y su contexto e
  inspeccione todo el diff de la vista previa antes de aplicarlo.
- [x] Mantener la vista previa sin escrituras y exigir una solicitud o aprobación
  explícita para usar `--apply`. Después, revisar `postAnalysis`, el diff y las
  pruebas pertinentes cuando sea posible.
- [x] Documentar en `README.md` la instalación global/local de OpenCode, Java 25,
  la compilación y la ubicación configurable del JAR.

### Fase 2 — Reducir la fricción de instalación

- [x] Automatizar la publicación de un JAR ejecutable versionado y de la skill
  empaquetada en una versión de GitHub para etiquetas que coincidan con el POM;
  generar sumas SHA-256 para ambos archivos.
- [x] Añadir un instalador idempotente para macOS/Linux que descargue una versión
  concreta, verifique las sumas e instale el JAR y la skill globalmente o solo en
  el proyecto.
- [ ] Publicar la primera etiqueta de versión que coincida con el POM y verificar
  que los archivos se pueden descargar y que funciona una instalación limpia con
  `scripts/install-javafix-skill.sh`. La automatización ya existe, pero requiere
  una publicación autorizada en el repositorio.
- [ ] Añadir instrucciones equivalentes para los agentes que adopten el formato
  Agent Skills estándar, manteniendo `SKILL.md` como fuente única y evitando
  instrucciones divergentes.
- [ ] Evaluar un plugin solo si se necesita una interfaz de un clic o callbacks
  del cliente. Un plugin no debe decidir si una reparación es semánticamente
  segura ni aplicar cambios sin aprobación.

## Contrato de comportamiento del agente

- Validación de cambios Git: analizar solo los `.java` preparados, sin preparar y
  nuevos; no confundir archivos eliminados con errores de Java. Informar si no hay
  archivos Java modificados.
- Análisis global: solo cuando se solicite; omitir `--files` e indicar
  explícitamente si el análisis queda incompleto.
- Evaluación: separar errores confirmados, diagnósticos contextuales y heurísticas;
  revisar el código cercano y no asumir que un diagnóstico es correcto solo
  porque lo haya emitido una herramienta.
- Reparación: aceptar solo el `repairId` del informe vigente; preparar una vista
  previa, revisar archivos y contenido, rechazar cambios incorrectos o fuera de
  alcance y pedir autorización antes de `--apply`.
- Validación posterior: leer `postAnalysis` y los cambios en los diagnósticos; no
  declarar resuelto un hallazgo si el análisis está incompleto o el diff no es
  correcto.

## Pruebas y criterios de aceptación

- Skill ejecutada con un proyecto que contiene archivos `.java` preparados, sin
  preparar, no rastreados y eliminados; la selección incluye exactamente los que
  existen.
- Lista de cambios sin archivos Java: no inicia accidentalmente un análisis
  completo.
- Solicitud de análisis completo: JavaFix se ejecuta sin `--files`.
- Resultados incompletos y heurísticos: se comunican con sus limitaciones.
- Vista previa: no se modifica ningún archivo fuente; no se usa `--apply` sin
  solicitud o aprobación.
- Aplicación autorizada: diff revisado y reanálisis posterior inspeccionado.
- Las instrucciones del README funcionan en una instalación limpia de OpenCode,
  global y local, con Java 25.

## Archivos relacionados

- Skill: `.opencode/skills/javafix/SKILL.md`
- Instrucciones de instalación y uso: `README.md`
- CLI y esquemas: `src/main/java/io/github/krandalf75/javafix/Main.java` y
  `src/main/resources/schema/`
- Publicación actual: consultar el workflow de GitHub Actions y la documentación
  de Maven antes de elegir el canal de instalación de la fase 2.
