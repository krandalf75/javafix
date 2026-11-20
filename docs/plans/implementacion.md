# Plan de implementación

## Objetivo

Construir una herramienta CLI para que una IA pueda analizar proyectos Java mediante JDTLS, obtener diagnósticos útiles y aplicar reparaciones explícitas y verificables.

## Fase 1 — Análisis de ficheros Java con JDTLS

**Objetivo de entrega:** una CLI ejecutable desde un JAR que incluye sus dependencias y la distribución fijada de JDTLS, recibe un proyecto Maven o Gradle y una lista opcional de ficheros Java, y devuelve un análisis JSON con diagnósticos, orientación de resolución y las acciones de corrección que JDTLS ofrece. Esta fase no escribe cambios.

### Checklist

- [x] Crear proyecto Maven Java 17 y empaquetar la CLI con dependencias de aplicación en un JAR ejecutable.
- [x] Definir interfaz `analyze` con descriptor/directorio de proyecto y selección explícita de ficheros `.java`; limitar la respuesta a esos objetivos.
- [x] Fijar el esquema JSON versionado: resumen, ficheros, diagnóstico nativo LSP (`source`, `code`, severidad, mensaje y rango), explicación y acciones JDTLS disponibles.
- [x] Fijar una versión de JDTLS y empaquetar su distribución en el artefacto; extraerla a una ubicación temporal/cache al ejecutar y arrancar el proceso servidor.
- [x] Incluir y conservar avisos/licencia EPL-2.0 de JDTLS y revisar las licencias/notices de dependencias redistribuidas en el JAR. (Avisos de dependencias directas/transitivas de JavaFix y recursos upstream de los plug-ins JDTLS.)
- [x] Implementar cierre del proceso y ciclo LSP, importación del proyecto, espera de diagnósticos y consulta de `textDocument/codeAction` por hallazgo.
- [x] Diferenciar acciones reales ofrecidas por JDTLS de orientación textual; informar cuando no haya quick fix y no aplicar acciones automáticamente.
- [x] Devolver errores accionables para configuración ausente, importación fallida, timeout, diagnósticos incompletos y proceso JDTLS no disponible.
- [x] Crear proyectos de prueba `acme` reproducibles para Maven y Gradle, con casos deliberados, diagnósticos esperados y casos con/sin quick fix.
- [x] Añadir pruebas unitarias del esquema/normalización, pruebas LSP con servidor controlado y pruebas de integración contra JDTLS en `acme`.
- [x] Documentar compilación, ejecución, argumentos, JSON, configuración de JDTLS y limitaciones.
- [x] Verificar con `mvn clean verify` y ejecutar el JAR empaquetado: `--help` y análisis CLI de un fixture `Inline Local` completados en macOS; la salida fue JSON completa y anunció `PMD/UnnecessaryLocalBeforeReturn` con `providerActions`. Se excluyeron las firmas de dependencias en el shade para evitar `Invalid signature file digest`.

**Criterio de aceptación:** la CLI analiza una lista de ficheros de un proyecto Maven o Gradle, devuelve los diagnósticos de JDTLS con ubicación y código original, describe cómo abordarlos, lista las acciones disponibles y no modifica el proyecto.

**Runtime acordado:** empaquetar JDTLS dentro del JAR, ejecutarlo como proceso hijo y usar la misma JVM; por tanto, el runtime mínimo será Java 25. El código de JavaFix puede mantener compatibilidad de compilación con Java 17, pero `java -jar` requerirá Java 25.

## Fases

### 1. Base del proyecto

- Configurar Maven con coordenadas `io.github.krandalf75:javafix:0.2.0` y compilar JavaFix para Java 25. Distribuir un JAR ejecutable con dependencias de aplicación y JDTLS incluidos; el runtime de `java -jar` será Java 25. Publicar en GitHub Packages y como asset público de GitHub Release.
- Definir paquetes para la interfaz de comandos, análisis, integración con JDTLS, reglas/reparaciones y salida.
- Añadir pruebas automatizadas, formato/lint y comandos reproducibles para compilar y probar.
- Documentar instalación, requisitos de JDTLS y configuración inicial en el README.

**Validación:** una compilación limpia y una prueba mínima se ejecutan con los comandos documentados.

### 2. CLI y selección de fuentes

**Objetivo:** hacer que la selección del proyecto y de los ficheros sea predecible para uso interactivo y por agentes, incluidos ficheros sueltos, sin salir del modo de solo análisis.

**Checklist**

- [x] Aceptar como contexto un `pom.xml`, `build.gradle`, `build.gradle.kts` o directorio.
- [x] Al recibir un directorio con varios descriptores, exigir que se indique uno de forma explícita.
- [x] Inferir el proyecto Maven/Gradle más cercano cuando `--project` apunta a un fichero Java suelto; dar un error accionable si no existe contexto.
- [x] Normalizar y ordenar rutas de forma determinista; eliminar duplicados, resolver rutas relativas al proyecto y rechazar rutas fuera de la raíz.
- [x] Añadir pruebas de symlinks (si el runner los permite) y rutas con espacios.
- [x] Admitir una selección explícita de ficheros `.java`, rechazar extensiones/paths inválidos y limitar la respuesta a los objetivos pedidos.
- [x] Excluir directorios generados `target`, `build`, `bin`, `out` y `.gradle` del descubrimiento.
- [x] Añadir caso para ficheros fuera de la raíz.
- [x] Añadir casos para directorios sin fuentes y descriptor ausente/ambiguo.
- [x] Confirmar en pruebas que el análisis no cambia fuentes ni configuración del proyecto.

**Validación/aceptación:** pruebas unitarias de selección y rutas, y pruebas Maven/Gradle de extremo a extremo para directorio, descriptor y fichero suelto; salida determinista solo con los objetivos solicitados.

### 3. Análisis con JDTLS

**Objetivo:** estabilizar el proveedor JDTLS para proyectos reales y conservar diagnósticos LSP fieles, completos y atribuibles a su fichero.

**Checklist**

- [x] Versionar un catálogo de códigos JDT observados en fixtures de la versión fijada y asociarlos a nombres legibles sin sustituir el código original.
- [x] Probar cambios de configuración del compilador; la emisión de avisos varía con las opciones del proyecto.
- [x] Probar varios ficheros abiertos, publicaciones repetidas y timeout parcial.
- [x] Probar terminación inesperada de JDTLS; proyectos grandes/lentos y límites de memoria quedan pendientes de CI/perfilado.
- [x] Verificar el ciclo LSP y la selección de plataforma en Linux, Windows y macOS mediante la matriz CI.
- [x] Mantener `source`/`code` de JDTLS separados de identificadores de otros proveedores; PMD y SpringBoot usan su origen, código local y `ruleId` propio.

**Ya resuelto en Fase 1:** transporte LSP básico, inicialización/importación, cierre, diagnósticos, rangos, acciones de código y timeout/incomplete. La matriz CI de Linux, Windows y macOS ha pasado.

**Validación/aceptación:** catálogo probado con fixtures versionados; la respuesta conserva ubicación y código nativos; fallos y resultados incompletos son distinguibles.

### 4. Reglas adicionales y proveedores

**Objetivo:** ampliar el análisis con reglas mantenibles de varios proveedores y atribuir cada hallazgo a su origen y código exactos.

**Checklist**

- [x] Evaluar analizadores locales por reglas disponibles, compatibilidad Java, licencia, mantenimiento y requisitos de ejecución.
- [x] Elegir PMD 7.29.0 para un conjunto acotado de reglas y mantener un proveedor independiente de JDTLS.
- [x] Conservar separados los diagnósticos nativos JDTLS y los hallazgos de proveedores adicionales mediante `source`, `ruleId` y `code`.
- [x] Añadir fixtures positivas y negativas para las reglas PMD y SpringBoot seleccionadas.
- [x] Añadir buenas prácticas Spring como proveedor independiente con heurísticas advisory para inyección, validación de configuración, transacciones y APIs.
- [x] Documentar reglas activadas, configuración, límites y licencias en README.

**Catálogos y límites:** JDTLS expone códigos nativos del compilador, versionados en `META-INF/jdtls-code-catalog.json`; las opciones del proyecto gobiernan muchos avisos configurables. PMD analiza las fuentes seleccionadas y no sustituye el análisis de flujo de datos de seguridad. Las reglas SpringBoot son heurísticas léxicas y no resuelven tipos ni contexto de proxies.

**Validación/aceptación:** fixtures reproducibles; cada hallazgo se atribuye a su proveedor y código, y solo se documentan reglas verificadas.

### 5. Reparaciones seguras

**Objetivo:** permitir que un agente solicite correcciones concretas, inspeccionables y reversibles sin convertir el análisis en una escritura implícita.

**Checklist**

- [x] Definir el contrato de reparaciones JDTLS y propias de JavaFix: ID determinista ligado al contenido, título/tipo de acción, origen (`jdtlsActions` o `providerActions`) y `WorkspaceEdit`; los límites y precondiciones de la reescritura Inline Local se describen junto a la regla.
- [x] Permitir que un plan JSON seleccione reparaciones mediante IDs emitidos por JavaFix, sin pedir a la IA que redacte los cambios.
- [x] Distinguir acciones `quickfix` de refactorizaciones JDTLS (`refactor.*`); solo quick fixes directos habilitados se ofrecen para aplicación automática. Las refactorizaciones se muestran como información y pueden requerir selección y parámetros.
- [x] Declarar `requiredParameters` en el informe y validarlos en el plan v1 antes de generar la edición. Extract Constant requiere `parameters.constantName`; Extract Method requiere `parameters.selection` y `parameters.methodName`. Los planes v1 antiguos por ID siguen válidos para acciones sin parámetros. El esquema y el runtime rechazan propiedades desconocidas, rangos inválidos y nombres Java inválidos.
- [x] Verificar `java/getRefactorEdit` de JDTLS 1.61.0 en modo headless para Extract Constant: acepta `command: extractConstant` y el rango, devuelve `WorkspaceEdit` más `java.action.rename`, pero no un parámetro `constantName`. La reparación parametrizada implementada usa `ASTRewrite` propio, no el comando interactivo de JDTLS.
- [x] Probar Inline Local por `textDocument/codeAction` con `only: ["refactor.inline"]`; el fixture headless no recibe esa acción, así que no se anuncia ni se aplica como reparación.
- [x] Revisar las reglas PMD frente a las acciones y refactorizaciones JDTLS; documentar como no reparables los casos que no tengan una acción verificada ni un reparador propio con precondiciones comprobadas. README y fixtures delimitan las reglas automáticas verificadas y los diagnósticos que no se anuncian como reparables.
- [x] Probar `PMD/UnnecessaryLocalBeforeReturn` → Inline Local Variable; el code action `refactor.inline` no aparece en el fixture y no se ofrece.
- [x] Probar `PMD/LambdaCanBeMethodReference` en el rango de la lambda; JDTLS ofrece otras acciones, pero no la conversión a method reference, así que no se vincula una reparación.
- [x] Añadir Extract Constant solo tras validar un fixture antes/después y el parámetro del plan; no inferir reparaciones para otras reglas PMD.
- [x] Para hallazgos PMD, correlacionar el diagnóstico nativo JDTLS equivalente o solicitar `textDocument/codeAction` a JDTLS en el rango del hallazgo; ofrecer la reparación solo cuando una fixture confirme que la acción devuelta corrige ese caso. `CloseResource` se correlaciona por rango; las otras reparaciones verificadas usan sus propias transformaciones acotadas o parámetros de selección explícitos, sin asumir equivalencias.
- [x] Empezar por `PMD/CloseResource` ↔ diagnóstico JDTLS de fuga; no inferir equivalencias para otras reglas PMD.
- [x] Vincular `PMD/CloseResource` a un quick fix JDTLS solo cuando coincidan rango y diagnóstico de fuga; la fixture `acme/gradle` verifica el ID y `PmdCloseResourceRepairIntegrationTest` aplica la reparación y confirma que desaparece al reanalizar.
- [x] Añadir vista previa de contenidos como comportamiento predeterminado y seleccionar reparaciones por ID.
- [x] Añadir aplicación solo con `repair --apply`; mantener `analyze` de solo lectura.
- [x] Detectar rangos solapados y planes obsoletos mediante IDs ligados al hash de cada fuente; revalidar bytes tras preparar temporales y antes de reemplazar, y probar con hook controlado que el rollback no pisa cambios externos (`MainTest.rollbackDoesNotOverwriteExternalChangesDetectedDuringMultiFileWrite`). La ventana TOCTOU no puede eliminarse para escritores no cooperativos.
- [x] Agrupar `WorkspaceEdit` directos compatibles de un mismo análisis y aplicar en lote; rechazar rangos solapados o planes obsoletos. Los cambios dependientes necesitan otro análisis/plan.
- [x] Preparar ediciones mult fichero antes de escribir, preservar permisos POSIX, y restaurar solo archivos que aún contienen los bytes escritos por JavaFix; los reemplazos multiarchivo no son atómicos como conjunto.
- [x] Preservar BOM UTF-8, finales CRLF, caracteres suplementarios y texto intacto; rechazar UTF-8 malformado antes de escribir. Otras codificaciones aún no están soportadas.
- [x] Empezar por quick fixes JDTLS deterministas, sin aplicar acciones deshabilitadas ni de supresión como si fueran arreglos.
- [x] Permitir que JavaFix invoque JDTLS para reparar hallazgos PMD aunque PMD no tenga quick fix propio; si JDTLS no devuelve una acción verificable, informar que el caso no tiene reparación automática (o añadir un reparador JavaFix determinista con precondiciones y fixtures propios). La integración se limita a `CloseResource` y Extract Method; Inline Local, Extract Constant y Lambda usan reparadores propios cubiertos por fixtures.
- [x] Reanalizar después de aplicar y reportar diagnósticos resueltos, persistentes y nuevos mediante `postAnalysis` y `diagnosticChanges`; comparar identidades sin rango para tolerar desplazamientos de línea.
- [x] Añadir cancelación cooperativa mediante `--cancel-file`, checkpoints durante LSP y transacciones, rollback de escrituras propias y pruebas de cancelación CLI durante JDTLS, pre-write y rollback multifichero.
- [x] Probar el flujo CLI no interactivo con dos reparaciones por ID en el mismo fichero, `--apply`, reanálisis, rechazo de selección duplicada y rechazo de plan obsoleto con código 2 sin alterar bytes; probar solapamientos antes de escribir con fixture unitaria.
- [x] Verificar la reparación PMD `CloseResource` de extremo a extremo: hallar ID vinculado, aplicar el `WorkspaceEdit` JDTLS y comprobar que el hallazgo desaparece en el reanálisis.

#### Ampliación de reparaciones: orden de implementación

**Enfoque:** usar JDTLS para generar las ediciones que ya sabe hacer. Si no expone una acción utilizable sin interacción, implementar solo transformaciones concretas con Eclipse JDT y `ASTRewrite`, no con expresiones regulares. Ambas vías deben producir ediciones para el mismo flujo de JavaFix; una acción que compila no se considera segura por ese solo hecho.

##### 1. Resolver acciones diferidas de JDTLS

- [x] `Main.java` ya anuncia `resolveSupport`. Si una acción habilitada tiene `data` pero no `edit`, JavaFix envía la acción original a `codeAction/resolve` en la misma sesión y evalúa la respuesta con los filtros de una edición directa.
- [x] No emitir un ID aplicable si la acción está deshabilitada, suprime el aviso, sigue sin edición o requiere un comando no soportado. `workspace/applyEdit` sigue deshabilitado: JDTLS propone y JavaFix valida y aplica. Pruebas controladas cubren resolución correcta, error de resolución, acciones deshabilitadas, supresión y refactorizaciones.
- [x] Separar el descubrimiento de `quickfix` y `refactor.*`. `LspSession.codeActions` admite `context.only` y conserva el rango exacto; las pruebas confirman que `refactor.inline` no se considera reparación automática. Un hallazgo PMD no se trata como diagnóstico JDTLS ni garantiza una acción equivalente.

##### 2. Probar `PMD/UseTryWithResources`

- [x] Analizar fixtures donde el recurso **ya se cierra en `finally`**. JDTLS 1.61.0 no devuelve un quick fix verificable para estos hallazgos; no se emite `repairId`. La reparación de `PMD/CloseResource` sigue siendo un caso distinto: corrige una fuga.
- [x] Cubrir también un `finally` con lógica adicional. No se anuncia reparación: además del posible cambio en orden/efectos, si fallan tanto el cuerpo como `close()`, try-with-resources puede cambiar qué excepción se propaga y cuál queda suprimida.
- [x] Mantener `PMD/UseTryWithResources` como diagnóstico no reparable automáticamente hasta disponer de una transformación demostrablemente equivalente y fixtures antes/después. La integración comprueba que ambos fixtures no anuncian `automaticFixAvailable`.

##### 3. Extract Constant parametrizado

- [x] Verificado que JDTLS 1.61.0 devuelve `WorkspaceEdit` y `java.action.rename` para `extractConstant`, pero no admite `constantName` en la petición headless.
- [x] En lugar de componer comandos interactivos, ofrecer una variante acotada de JavaFix con `ASTRewrite`: un literal `String` señalado por `PMD/AvoidDuplicateLiterals`, nombre `constantName` suministrado explícitamente en el plan y campo `private static final String`. Sustituir las ocurrencias con el mismo valor en la clase contenedora; rechazar literales no localizados, clases no admitidas, nombres inválidos y colisiones.
- [x] Cubrir validación de parámetros, preview sin escritura, `--apply`, compilación/comportamiento antes y después y desaparición del diagnóstico PMD en el reanálisis.

##### 4. Implementar reparaciones propias solo donde falte una acción

- [x] **Inline Local (`PMD/UnnecessaryLocalBeforeReturn`).** JDTLS 1.61.0 no ofrece `refactor.inline` en el fixture. JavaFix implementa con Eclipse JDT `ASTRewrite` el caso restringido de una variable inicializada, con un único uso como expresión del `return` inmediatamente posterior. Solo se ofrece con análisis JDTLS completo y sin errores, exige una única candidata en la línea y preserva el tipo declarado mediante cast; rechaza `var`, usos adicionales, declaraciones múltiples, anotaciones, inicializadores/rangos con comentarios y casos ambiguos.
- [x] Añadir un fixture negativo y de comportamiento para **Inline Local**: la prueba compila antes y después `Object f() { long n = 1; return n; }` y confirma que ambos devuelven un `Long`, frente a la diferencia que introduciría `return 1;` (`Integer`). La prueba end-to-end verifica preview sin escritura, `--apply` y desaparición del hallazgo.
- [x] **Lambda a referencia de método (`PMD/LambdaCanBeMethodReference`).** JDTLS 1.61.0 ofrece otras acciones, pero no la conversión. JavaFix aplica `ASTRewrite` solo a una llamada estática cualificada por un nombre de tipo, con todos los parámetros reenviados directamente y en el mismo orden; vuelve a resolver la referencia y exige el mismo binding de método/overload. Rechaza receptores de instancia (`System.out.println`), métodos genéricos, argumentos transformados/reordenados y casos sin bindings. Pruebas cubren preview, apply, compilación, resultado observable, desaparición del hallazgo y el rechazo del receptor con semántica de captura distinta.

##### 5. Extract Method como refactorización explícita

- [x] Verificar `java/getRefactorEdit` de JDTLS 1.61.0 con la selección exacta: devuelve una edición y `java.action.rename`; JDTLS no recibe el nombre en esa petición.
- [x] Exponer Extract Method para diagnósticos `PMD/CognitiveComplexity` y `PMD/NcssCount` con `selection` `{start,end}` en posiciones LSP 0-based y `methodName` obligatorios en el plan. La reparación vuelve a consultar JDTLS, aplica la primera edición solo en memoria y envía `textDocument/rename` con el nombre aportado; el WorkspaceEdit final es una edición respecto al fuente original.
- [x] Probar preview sin escritura, `--apply`, reanálisis completo y rechazo de selección/nombre inválidos. La prueba de cancelación entre la edición Extract Method de JDTLS y el renombrado verifica que el fuente original queda intacto. No se elige bloque ni se inventa nombre automáticamente.

##### 6. Validar el flujo común antes de habilitar cada reparación

- [x] Mantener el plan JSON y la vista previa por defecto, sin escritura en el proyecto. Aplicar únicamente con `--apply`, regenerar y validar la edición contra las fuentes originales y rechazar planes obsoletos, rutas fuera del proyecto y ediciones solapadas. Para las ediciones compuestas, probar la cancelación entre pasos y comprobar que los originales no cambian (`RepairPreviewIntegrationTest`, `JdtlsIntegrationTest`, `MainTest`, `ExtractMethodRepairIntegrationTest`).
- [x] Cubrir cada reparación con fixtures positivos de vista previa/aplicación y fixtures de rechazo de sus precondiciones; los casos sin una acción verificada se comprueban como no reparables. El reanálisis confirma los hallazgos resueltos y los diagnósticos posteriores. Las pruebas de Inline Local y Lambda comprueban además la preservación del comportamiento (`Pmd*RepairIntegrationTest`, `ExtractMethodRepairIntegrationTest`, `JdtlsIntegrationTest`, `InlineLocalRepairTest`, `LambdaMethodReferenceRepairTest`).

**Validación/aceptación:** pruebas con fixtures antes/después; ninguna modificación ocurre sin una opción explícita y cada cambio es auditable.

### 6. Uso por agentes y estabilización

**Objetivo:** consolidar una interfaz no interactiva y versionada que un agente pueda usar de forma segura desde análisis hasta reparación.

**Checklist**

- [x] Versionar esquemas de análisis, plan y resultado de reparación v1 en `src/main/resources/schema/`, incluidos estados `completed`/`incomplete`, `preview`/`applied` y el código de error CLI `2`.
- [x] Definir compatibilidad v1: los planes rechazan propiedades desconocidas; los resultados pueden añadir campos opcionales; cualquier cambio incompatible requiere incrementar `schemaVersion`.
- [x] Añadir pruebas end-to-end desde análisis hasta vista previa y aplicación de reparaciones.
- [x] Añadir casos end-to-end de preview sin escritura, aplicación CLI de múltiples IDs y reparación PMD seguida de reanálisis.
- [x] Documentar instalación, ejemplos, reglas/proveedores cubiertos, limitaciones y solución de errores frecuentes.
- [x] Documentar cómo añadir un proveedor, regla o reparación con sus pruebas de fixture.
- [x] Crear `.opencode/skills/javafix/SKILL.md` como skill de ejemplo para agentes: requisito Java 25, análisis, selección de `repairId`, plan JSON, vista previa y aplicación explícita.
- [x] Validar CLI mediante subprocess sin TTY: JSON-only en stdout, error de uso en stderr/código 2 y flujo analyze/preview con rutas de proyecto/plan con espacios (`CliProcessIntegrationTest`).
- [x] Confirmar publicación/consumo real del artefacto en GitHub Packages: `0.1.0` figura como público; el workflow de publicación del tag `v0.1.0` terminó correctamente (run `44532382122`) y el consumo Maven autenticado se valida con `dependency:get` en una caché vacía. El registry Maven requiere autenticación aun para paquetes públicos (la descarga anónima responde 401), así que se verifica visibilidad pública y consumo autenticado, no descarga anónima.

**Validación/aceptación:** un agente puede analizar, elegir una acción, revisar su diff y solicitar su aplicación sin prompts interactivos; la salida y códigos de estado son estables y documentados.

## Criterios transversales

- El análisis no modifica el código por defecto.
- Los errores deben distinguir problemas de entrada, configuración/JDTLS y fallos de análisis.
- Las reparaciones deben ser explícitas, auditables y cubiertas por pruebas con fixtures.
- Enumerar los proveedores y reglas realmente soportados, sin prometer cobertura más amplia que la verificada.

## Decisiones pendientes

- Elegir los analizadores locales concretos; verificar compatibilidad de reglas, disponibilidad y licencia antes de fijar la arquitectura.
- El MVP debe cubrir proyectos Maven y Gradle, además de ficheros sueltos; concretar cómo seleccionar/importar el descriptor y cuándo inferirlo desde un directorio.
- Confirmar qué reglas candidatas están disponibles en el analizador local elegido y cuáles admiten reparación automática segura.

## Fuentes consultadas

- [Eclipse JDT Language Server: capacidades, ejecución y requisitos](https://github.com/eclipse-jdtls/eclipse.jdt.ls)
- [Eclipse Java Compiler: configuración de errores y avisos](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/preferences/java/compiler/ref-preferences-errors-warnings.htm)
- [Eclipse JDT Core: opciones del compilador y análisis de null](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_options.htm)

### Buenas prácticas de Spring Boot

JDTLS aporta diagnóstico Java general y resolución de tipos, pero no debe considerarse un analizador de buenas prácticas de Spring Boot. JavaFix incluye un proveedor independiente con reglas advisory para inyección, validación de configuración, transacciones y APIs; mantiene sus hallazgos identificados y documenta que no resuelve tipos ni contexto de proxies. OpenRewrite puede aportar recetas de migración/reparación, pero no sustituye al detector.

#### Comparación y selección para el proveedor adicional

| Analizador | Java 17/25 | Maven/Gradle | Licencia y runtime | Decisión |
| --- | --- | --- | --- | --- |
| Eclipse JDTLS 1.61.0 | JavaFix compila y se ejecuta con Java 25 | Importa proyectos Maven/Gradle | EPL-2.0 y licencias upstream; proceso hijo empaquetado | Mantener para diagnósticos nativos del compilador |
| PMD 7.29.0 | Compatibilidad de parser con Java 25 verificada; librería compatible con baseline de JavaFix 17 | Proveedor recibe los ficheros seleccionados; no importa el descriptor | BSD-style; librería Java en el runtime | Seleccionado para `UnusedLocalVariable` y `CloseResource` |
| OpenRewrite | Recetas disponibles dependen de la versión y del nivel Java | Tiene integración con Maven y Gradle | Ejecución Java; enfoque principal de recetas de transformación | No seleccionado como detector general; puede considerarse para una fase de reparaciones explícitas |

PMD aplica el parser a los ficheros seleccionados, sin credenciales ni servidor externo; se omiten ficheros con errores sintácticos JDTLS. Las reglas SpringBoot de JavaFix son heurísticas léxicas con limitaciones descritas en README, no una integración semántica de Spring ni equivalencia con catálogos externos.
