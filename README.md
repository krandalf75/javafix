# JavaFix

CLI Java para analizar fuentes de un proyecto con JDTLS y PMD. Devuelve diagnósticos atribuibles al proveedor y acciones de corrección disponibles; no modifica archivos.

## Requisitos

- Java 25 o superior para compilar y ejecutar JavaFix y el JDTLS incluido; el artefacto se compila para Java 25 y Maven falla al inicio si se usa una versión anterior.
- Maven 3.9+ y acceso a Internet para compilar: el build descarga la distribución fijada de JDTLS 1.61.0 y la empaqueta dentro del JAR.
- El JAR incluye los avisos de terceros y licencias de Eclipse, PMD y dependencias; consulta `META-INF/licenses/`.

El JAR está preparado para macOS (Intel/Apple Silicon), Linux (x86_64/ARM64) y Windows (x86_64). La selección de la configuración del servidor es automática. La integración se ha ejecutado en macOS; Windows y Linux aún requieren validación en CI.

## Instalar la skill de IA (OpenCode)

La skill permite pedir al agente que valide los archivos Java modificados en el
worktree antes de subirlos o que analice todo el proyecto. El agente debe revisar
los diagnósticos y la vista previa de cada reparación antes de aplicarla; el
análisis no modifica archivos y `--apply` solo se usa con aprobación explícita.

La instalación global (macOS/Linux) puede hacerse descargando el instalador y
revisándolo antes de ejecutarlo:

```sh
curl -fsSLo "$HOME/install-javafix-skill.sh" \
  https://raw.githubusercontent.com/krandalf75/javafix/main/scripts/install-javafix-skill.sh
less "$HOME/install-javafix-skill.sh"
sh "$HOME/install-javafix-skill.sh"
```

El JAR ejecutable también se puede descargar directamente, sin declarar una
dependencia Maven:

```sh
curl -fL https://github.com/krandalf75/javafix/releases/latest/download/javafix.jar \
  -o javafix.jar
```

La URL apunta al asset público de la última GitHub Release; el instalador verifica
su checksum antes de instalarlo.

El instalador obtiene la última release (o la versión indicada con
`--version 0.2.0`), verifica SHA-256 y coloca la skill en
`~/.config/opencode/skills/javafix`. El JAR queda en
`~/.local/share/javafix/javafix.jar`. Para instalar la skill solo en el proyecto
actual, ejecuta `sh "$HOME/install-javafix-skill.sh" --local`; también se puede
indicar un directorio con `--local /ruta/al/proyecto`. El instalador no reemplaza
una skill existente que no sea un enlace simbólico.

Java 25 o superior sigue siendo necesario para ejecutar JavaFix y el JDTLS
incluido. La descarga directa o el instalador no requieren compilar el proyecto.
Para desarrollo o contribución sí se puede compilar desde el código fuente:

```sh
git clone https://github.com/krandalf75/javafix.git ~/.local/share/javafix
cd ~/.local/share/javafix
mvn clean verify
mkdir -p ~/.config/opencode/skills
ln -s ~/.local/share/javafix/.opencode/skills/javafix \
  ~/.config/opencode/skills/javafix
export JAVAFIX_JAR="$HOME/.local/share/javafix/target/javafix-0.2.0.jar"
```

Obre/recarrega OpenCode perquè detecti la skill.

Demana a l’agent, per exemple, «valida els Java modificats abans del commit» o
«escaneja tot el projecte i proposa correccions». Per als canvis de Git, la
skill inclou fitxers staged, unstaged i Java nous no seguits; per a l’escaneig
complet, no limita els fitxers. L’agent ha d’avaluar si cada resultat és real i
si el diff proposat és correcte. La skill també està disponible per a
instal·lació local al projecte a `.opencode/skills/javafix/SKILL.md`.

## Compilar

```sh
mvn clean verify
```

El artefacto ejecutable queda en `target/javafix-0.2.0.jar`.

## Analizar

```sh
java -jar target/javafix-0.2.0.jar analyze \
  --project /ruta/al/proyecto/pom.xml \
  --files src/main/java/example/A.java src/main/java/example/B.java
```

El proyecto también puede indicarse como directorio o mediante `build.gradle`/`build.gradle.kts`. Sin `--files`, se analizan los `.java` encontrados debajo de la raíz. Las rutas relativas se resuelven respecto a la raíz del proyecto.

La salida JSON va por stdout; los errores de uso, del plan o de JDTLS se escriben en stderr y devuelven código `2`. Con código `0`, `analyze` informa `completed`/`incomplete`; `repair` devuelve `preview` por defecto o `applied` con `--apply`. Si JDTLS no publica diagnósticos para todos los ficheros antes del límite, el análisis queda en `"status": "incomplete"` y `"analysisComplete": false`. Se preservan los códigos y la fuente LSP de JDTLS. `range` usa líneas/columnas desde 1; `lspRange` conserva el rango original LSP, que empieza en 0 y tiene el extremo final exclusivo. Cada diagnóstico incluye acciones solicitadas a JDTLS cuando las hay. JavaFix restaura los metadatos Eclipse de proyecto que JDTLS pueda actualizar durante la importación.

El análisis PMD 7.29.0 activa las reglas listadas en esta documentación; los errores sintácticos Java se excluyen del pase PMD. El proveedor SpringBoot aplica heurísticas advisory sobre código fuente y no necesita dependencias Spring en tiempo de ejecución.

### Esquema JSON (versión 1)

- Raíz: `schemaVersion`, `status` (`completed` o `incomplete`), `providers`, `summary` y `files`.
- `summary`: número de ficheros y diagnósticos, contadores `errors`/`warnings` y `analysisComplete`.
- Cada fichero: ruta relativa al proyecto y lista `diagnostics`.
- Cada diagnóstico: `source`, `code` nativo LSP (se conserva su tipo), `ruleId` (`null` para JDTLS), `severity`, `message`, `range`, `lspRange` y `resolution`.
- `resolution` separa la explicación textual de `jdtlsActions` (JDTLS) o `providerActions` (otros proveedores). Las acciones JDTLS con `WorkspaceEdit` incluyen `repairId`; `automaticFixAvailable` no cuenta acciones que solo suprimen el aviso.
- Los planes v1 pueden añadir `parameters` por reparación. Actualmente `PMD/AvoidDuplicateLiterals` ofrece Extract Constant para literales `String` repetidos en una clase y exige `parameters.constantName`; los planes antiguos por ID siguen siendo válidos para acciones sin parámetros.
- En `PMD/CognitiveComplexity` y `PMD/NcssCount`, el Extract Method explícito exige `parameters.selection` (rango LSP de 0-based `{start,end}`) y `parameters.methodName`. JavaFix pasa el rango exacto a JDTLS, aplica su edición en memoria y resuelve el renombrado con el nombre del plan antes de exponer una única edición para preview/apply.
- El contrato de análisis, plan y resultado de reparación está versionado en `src/main/resources/schema/{analysis-v1,repair-plan-v1,repair-result-v1}.schema.json`. Los campos desconocidos de los resultados se reservan para extensiones compatibles; los planes v1 rechazan campos desconocidos. Los cambios incompatibles requieren una nueva versión del esquema.

### Reparar (versión inicial)

Guarda los `repairId` emitidos por `analyze` en un plan:

```json
{"schemaVersion":"1","repairs":["ID_COPIADO_DEL_INFORME"]}
```

Para Extract Constant, usa el `repairId` del `providerAction` de `PMD/AvoidDuplicateLiterals` y proporciona el nombre solicitado:

```json
{"schemaVersion":"1","repairs":[{"id":"ID_COPIADO_DEL_INFORME","parameters":{"constantName":"MESSAGE"}}]}
```

El Extract Constant actual usa `ASTRewrite` y se limita a literales `String` de una clase Java: valida el identificador y las colisiones, crea un campo `private static final String` y sustituye las ocurrencias iguales de esa clase. El nombre y los parámetros se validan al reconstruir la reparación; la vista previa sigue siendo el comportamiento predeterminado.

Para Extract Method, selecciona explícitamente el rango de código en coordenadas LSP (líneas/caracteres desde cero) y copia el `repairId` del diagnóstico `PMD/CognitiveComplexity` o `PMD/NcssCount`:

```json
{"schemaVersion":"1","repairs":[{"id":"ID_COPIADO_DEL_INFORME","parameters":{"methodName":"calculateDouble","selection":{"start":{"line":3,"character":4},"end":{"line":3,"character":31}}}}]}
```

```sh
java -jar target/javafix-0.2.0.jar repair --project /ruta/proyecto --plan repair.json
java -jar target/javafix-0.2.0.jar repair --project /ruta/proyecto --plan repair.json --apply
```

Sin `--apply` no se modifica ningún archivo; la respuesta muestra el contenido propuesto. La aplicación vuelve a analizar y regenerar las acciones, por lo que rechaza IDs que ya no existen; también valida que todos los destinos pertenezcan al proyecto y que los rangos no se solapen. Las ediciones multiarchivo se preparan antes de escribir y se restauran si falla una escritura. JavaFix resuelve acciones diferidas `codeAction/resolve` y ejecuta quick fixes JDTLS habilitados que terminan en un `WorkspaceEdit`, además de reparaciones propias explícitamente verificadas en `providerActions`. No ejecuta commands ni refactorizaciones JDTLS interactivas; las reparaciones JavaFix con parámetros se validan y generan localmente antes de crear la edición. El único vínculo PMD→JDTLS habilitado y verificado con fixture antes/después es `PMD/CloseResource`. `PMD/UnnecessaryLocalBeforeReturn` usa la reescritura AST propia y restringida descrita arriba. Después de aplicar, la respuesta incluye `postAnalysis` y `diagnosticChanges.resolved`, `.persistent` y `.new`. `diagnosticChanges.complete` es `false` si el análisis inicial o el posterior quedó incompleto. La identidad compara ruta, proveedor, código/regla y mensaje (no el rango) para tolerar desplazamientos de línea; hallazgos repetidos se comparan como multiconjunto.

JavaFix procesa fuentes como UTF-8 y rechaza bytes UTF-8 inválidos antes de aplicar cambios. Las ediciones conservan el BOM UTF-8 y los finales de línea existentes fuera del rango editado; se preservan permisos POSIX cuando el sistema de archivos los expone. La escritura temporal revalida el contenido justo antes de cada reemplazo y evita restaurar encima de cambios externos detectados durante rollback, pero no puede garantizar atomicidad conjunta de varios archivos ni excluir carreras de escritores que no respeten bloqueos.

La cancelación cooperativa se solicita creando un archivo todavía inexistente indicado por `--cancel-file`; JavaFix lo comprueba entre peticiones LSP y pasos de escritura. Ejecuta el comando en un terminal:

```sh
java -jar target/javafix-0.2.0.jar analyze --project /ruta/proyecto --cancel-file /tmp/javafix.cancel
```

Desde otro terminal, solicita cancelación con `touch /tmp/javafix.cancel`. Si se cancela durante una aplicación multifichero, JavaFix revierte sus escrituras completadas cuando los bytes siguen siendo los escritos por JavaFix; conserva cambios externos detectados. Después de confirmar la transacción de escritura, completa el reanálisis para devolver un resultado no ambiguo. La cancelación no elimina la ventana de carrera TOCTOU con escritores externos; revisa la vista previa y conserva control de versiones o una copia de seguridad.

La inspección y fixture headless de JDTLS 1.61.0 confirman que `java/getRefactorEdit` acepta `extractConstant` y un rango, y devuelve un `WorkspaceEdit` junto con `java.action.rename`. La API no ofrece un campo `constantName`; por eso el flujo parametrizado descrito arriba se implementa localmente con Eclipse JDT `ASTRewrite`, sin ejecutar el comando interactivo de renombrado de JDTLS.

JDTLS 1.61.0 no ofrece `refactor.inline` para el fixture probado. Para `PMD/UnnecessaryLocalBeforeReturn`, JavaFix dispone ahora de una reescritura AST deliberadamente restringida: solo elimina una variable inicializada, con un único uso como expresión del `return` inmediatamente siguiente, y conserva la conversión de tipo con un cast explícito. Rechaza `var`, anotaciones, comentarios en el rango, declaraciones con varias variables, usos adicionales y casos no inequívocos.

**Reparaciones verificadas actualmente: cinco familias.** (1) Imports no utilizados (`UnusedImport` → Remove unused import). (2) Fugas de recursos: JDTLS `UnclosedCloseable` y PMD `CloseResource` usan la reparación JDTLS try-with-resources. (3) `PMD/UnnecessaryLocalBeforeReturn` usa la reescritura AST restringida descrita arriba. (4) `PMD/AvoidDuplicateLiterals` usa Extract Constant parametrizado para cadenas repetidas en una clase. (5) `PMD/LambdaCanBeMethodReference` admite solo llamadas estáticas seguras y con bindings verificados; las lambdas con receptor de instancia siguen siendo solo diagnósticos. Ninguna otra regla PMD se cuenta como reparable de extremo a extremo.

Extract Method es una refactorización explícita adicional, no un arreglo automático del diagnóstico PMD: el usuario elige tanto el rango como el nombre en el plan.

La conversión de lambda exige una llamada estática sobre un nombre de tipo y argumentos que reenvían, sin cambios, todos los parámetros de la lambda en el mismo orden. JavaFix vuelve a resolver el method reference y compara el binding con la sobrecarga original. Rechaza, entre otros, `x -> System.out.println(x)`: convertirlo a `System.out::println` adelantaría la captura/evaluación del receptor y podría cambiar el comportamiento.

### Reglas SpringBoot

| Proveedor | Regla | Disponibilidad | Acción automática |
| --- | --- | --- | --- |
| SpringBoot (heurísticas JavaFix) | `SpringBoot/FieldInjection` | `@Autowired` en campo | No |
| SpringBoot (heurísticas JavaFix) | `SpringBoot/ConfigurationValidation` | Restricciones de configuración sin `@Validated` | No |
| SpringBoot (heurísticas JavaFix) | `SpringBoot/PrivateTransactionalMethod` | Método `private` anotado `@Transactional` | No |
| SpringBoot (heurísticas JavaFix) | `SpringBoot/GetMappingRequestBody` | `@RequestBody` en método `@GetMapping` | No |

PMD se distribuye bajo BSD-style license; se incluye su licencia en `META-INF/licenses/`.

Las reglas SpringBoot son comprobaciones léxicas conservadoras, sin resolución de tipos ni análisis del contexto de proxies/configuración. Son recomendaciones para revisión, no errores concluyentes; pueden no reconocer anotaciones importadas con alias ni patrones complejos.

### Reglas PMD activadas

El ruleset verificado activa:

- Código sin uso/recursos: `UnusedAssignment`, `UnusedPrivateField`, `UnusedPrivateMethod`, `UseTryWithResources`, `UnusedLocalVariable`, `CloseResource`.

`PMD/UseTryWithResources` es diagnóstico únicamente: los fixtures con cierre manual en `finally` (incluido `finally` con lógica adicional) no reciben una edición JDTLS verificable y no anuncian reparación automática. No se reutiliza para esta regla la reparación de fugas `PMD/CloseResource`.
- Excepciones: `AvoidPrintStackTrace`, `AvoidCatchingGenericException`, `PreserveStackTrace`, `EmptyCatchBlock`, `AvoidThrowingNullPointerException`, `AvoidThrowingRawExceptionTypes`, `SignatureDeclareThrowsException`.
- Null y lógica: `BrokenNullCheck`, `EqualsNull`, `ReturnEmptyCollectionRatherThanNull`, `UnusedReturnValue`, `UselessPureMethodCall`, `AssignmentInOperand`.
- Control y complejidad: `SwitchStmtsShouldHaveDefault`, `AvoidDeeplyNestedIfStmts`, `CognitiveComplexity`, `CyclomaticComplexity`, `ControlStatementBraces`.
- API/estilo/rendimiento: `UseCollectionIsEmpty`, `UseStandardCharsets`, `UnnecessaryImport`, `UnnecessaryLocalBeforeReturn`, `LambdaCanBeMethodReference`, `UseDiamondOperator`, `VariableCanBeInlined`, `ClassNamingConventions`, `FieldNamingConventions`, `FormalParameterNamingConventions`, `LocalVariableNamingConventions`, `MethodNamingConventions`, `SystemPrintln`, `AvoidInstantiatingObjectsInLoops`, `InefficientStringBuffering`, `UseStringBufferForStringAppends`, `UseUtilityClass`.
- Seguridad/concurrencia/tests: `HardCodedCryptoKey`, `InsecureCryptoIv`, `DoubleCheckedLocking`, `JUnitTestsShouldIncludeAssert`.

PMD no hace análisis de flujo de datos suficiente para afirmar que cubre desreferencias nulas arbitrarias, XXE o inyección SQL; esos casos no se marcan como cubiertos. JDTLS/JDT puede detectar diagnósticos adicionales de compilador, incluidos miembros privados sin uso o avisos de nulabilidad, cuando las preferencias del proyecto los habilitan. El catálogo JDTLS versionado contiene solo códigos observados en los fixtures; no representa todos los IDs posibles del compilador.

Este producto incluye software desarrollado en parte con apoyo de la Defense Advanced Research Projects Agency (DARPA).

Los errores de argumentos, importación, inicio del servidor y timeout de peticiones LSP se escriben en stderr y terminan con código `2`. Un timeout esperando diagnósticos produce un JSON válido con estado `incomplete`, para no confundir una respuesta parcial con un análisis completo.

## Pruebas de integración ACME

- `acme/maven`: proyecto Maven con fuentes deliberadamente problemáticas.
- `acme/gradle`: proyecto Gradle con fuentes deliberadamente problemáticas.

```sh
java -jar target/javafix-0.2.0.jar analyze --project acme/maven/pom.xml
java -jar target/javafix-0.2.0.jar analyze --project acme/gradle/build.gradle
```

La primera compilación necesita descargar JDTLS desde Eclipse. En la primera ejecución, JavaFix extrae el servidor a `~/.cache/javafix/` y crea un workspace temporal.

`.java-version` fija Java 25 para herramientas que reconocen este formato. `mvn clean verify` requiere Java 25 o superior y ejecuta pruebas unitarias, un servidor LSP controlado y pruebas de integración Maven/Gradle. GitHub Actions valida Java 25 en Linux, Windows y macOS.

## Publicar en GitHub Packages

Las coordenadas Maven son `io.github.krandalf75:javafix:0.2.0`. Configura en `~/.m2/settings.xml` un servidor con id `github` y credenciales de GitHub Packages; después ejecuta:

```sh
mvn deploy
```

No guardes tokens en el repositorio.

GitHub Actions publica automáticamente al enviar un tag `v*`, siempre que el tag coincida exactamente con la versión del POM (por ejemplo, `v0.2.0`). El workflow `.github/workflows/publish.yml` ejecuta `clean deploy` con `GITHUB_TOKEN` y permiso `packages: write`, y crea una GitHub Release con el JAR ejecutable (versionado y como `javafix.jar`), el archivo de la skill y `SHA256SUMS`.

### Consumir como dependencia Maven

El registry Maven de GitHub exige autenticación para descargar paquetes incluso cuando su visibilidad es pública. Usa credenciales con permiso `read:packages` y acceso al paquete; configura variables de entorno `GITHUB_ACTOR` y `GITHUB_TOKEN` (no escribas el token en el POM):

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

```xml
<!-- pom.xml del consumidor -->
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/krandalf75/javafix</url>
  </repository>
</repositories>
<dependencies>
  <dependency>
    <groupId>io.github.krandalf75</groupId>
    <artifactId>javafix</artifactId>
    <version>0.2.0</version>
  </dependency>
</dependencies>
```

El acceso y la visibilidad del paquete se administran en GitHub; estos ejemplos no confirman que la versión esté publicada ni que el paquete sea público.

## Solución de problemas

- **Java anterior a 25:** revisa `java -version`, selecciona un JDK 25+ y vuelve a ejecutar Maven.
- **Fallo al descargar o iniciar JDTLS:** comprueba el acceso a Internet a la distribución de Eclipse y que el sistema operativo/arquitectura esté soportado.
- **`status: incomplete`:** JavaFix no recibió diagnósticos para todos los archivos dentro del límite; no interpretes la ausencia de avisos como resultado completo y repite el análisis.
- **Plan obsoleto o conflicto de rangos:** vuelve a ejecutar `analyze`, genera un plan nuevo solo con los IDs actuales y resuelve manualmente los cambios concurrentes antes de reintentar.
- **Código de salida 2:** consulta stderr para el error de argumentos, plan o JDTLS; los resultados JSON válidos de análisis incompleto siguen usando código 0.

## Añadir reglas o reparaciones

1. Para un proveedor nuevo, implementa el análisis en su `*RuleProvider` y registra diagnósticos con el contrato existente (`source`, `code`/`ruleId`, `severity`, `message`, `range`, `resolution`).
2. Añade fixtures positivas y negativas pequeñas y deterministas; verifica tanto hallazgos esperados como falsos positivos relevantes en `*RuleProviderTest`.
3. Para vincular una reparación, captura la acción real de JDTLS, exige un `WorkspaceEdit` directo y comprueba precondiciones, rangos y cambios fuera del objetivo.
4. Añade una prueba de integración antes/después que aplique la acción mediante un plan y confirme el reanálisis; no marques una regla como reparable por solo detectar el diagnóstico o recibir un command interactivo.
5. Actualiza los esquemas solo con cambios compatibles en v1; un cambio incompatible requiere nueva versión de esquema, documentación y fixtures.
