# android/

Esqueleto de la app (BOOTSTRAP fase 3). No contiene lógica de features: el módulo `alarmas`
existe vacío y lo completa `/feature alarmas`.

```
app/              Application, FeatureRegistry (lista explícita), NavHost, wiring manual
core/events/      sobre, Room, EventStore, Projection + registro, validador JSON, conformance
core/ui/          tema, tokens de espaciado y 10 componentes Compose
core/sync/        WorkManager: PushPullWorker, BlobUploadWorker, cursor en DataStore
feature/alarmas/  FeatureModule vacío; proyecciones futuras en src/domain/kotlin
```

## Requisitos

- JDK 17 (AGP 8.7). El proyecto no fija `org.gradle.java.home`: exportá `JAVA_HOME` antes de
  invocar Gradle.
- SDK de Android con la plataforma 34. `android/local.properties` (ignorado por git) apunta a él:
  `sdk.dir=/ruta/al/Sdk`.

## Comandos

```
./gradlew :core:events:test          # tests JVM del núcleo
./gradlew :feature:alarmas:test      # proyecciones de un feature (JVM)
./gradlew :app:assembleDebug         # APK de debug
```

## Reglas de build

- `android/build.gradle.kts` falla la configuración si un `:feature:*` depende de otro feature o de
  `:app`, o si un `:core:*` depende de un feature o de `:app`. La única excepción es
  `:core:events` en configuraciones `test*`, que necesita agregar los módulos de features para
  correr la conformidad.
- `:core:events` copia `../schema` a los assets del variant debug (`copyDebugSchemas`). El
  `SchemaEventValidator` valida sobre y payload contra esos JSON Schema; en release no se valida.
- Las versiones están pinneadas en `gradle/libs.versions.toml`.

## Conformidad

`ConformanceTest` recorre `schema/features/*/fixtures/**/*.json` y despacha por el
`ProjectionRegistry` agregado de todos los `FeatureModule` listados en
`core/events/src/test/.../TestFeatures.kt`.

Mientras ningún feature registre proyecciones, ese test se marca como *skipped* (el esqueleto no
tiene lógica de negocio). En cuanto el primer `FeatureModule` exponga una proyección, el test corre
estricto: una proyección desconocida en un fixture es un fallo, no un skip. `FixtureLoaderTest`
valida igualmente que todos los fixtures existentes parseen y decodifiquen a `Envelope`.
