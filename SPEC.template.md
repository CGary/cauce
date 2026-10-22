# Feature: <nombre>

<!--
Este archivo es la ÚNICA fuente de verdad del feature. Describe el estado deseado.
El agente no lee "cambios"; lee este archivo, compara con el código (git diff del
SPEC + código actual) y hace converger android/, server/, web/ y schema/.
Secciones vacías se escriben "Ninguno/a", no se borran.
-->

| Campo        | Valor |
|--------------|-------|
| id           | `<snake_case>` — nombre del módulo Gradle (`:feature:<id>`), del paquete Go (`server/internal/<id>`) y del directorio en `schema/features/<id>/` |
| estado       | `activo` \| `pausado` \| `eliminado` |
| versión spec | `N` — subir en cada cambio de comportamiento |
| superficies  | `android` \| `server` \| `worker` \| `web` (las que este feature toca) |

## 1. Problema

<!-- 1–3 líneas. Qué fricción real elimina. Si no se puede escribir, el feature no existe. -->

## 2. Proceso de negocio

<!--
Reglas numeradas, en lenguaje llano, sin tecnología. Esta es la sección que más se edita.
Cada regla debe ser verificable. Si una regla implica una proyección, nómbrala: (→ P2).
-->

1. …
2. …

## 3. Eventos

<!--
Eventos que ESTE feature produce. Su schema vive en schema/events/<tipo>.vN.json y este
feature es su dueño. Cambio incompatible = tipo nuevo vN+1, nunca editar vN.
"productor": app | server | worker:<nombre>
-->

| Tipo | v | Productor | Campos (además de `id`, `user_id`, `ts`, `device_id`) |
|------|---|-----------|-------------------------------------------------------|
| `<tipo>` | 1 | app | `campo: tipo` … |

### Eventos consumidos de otros features

<!-- Si esta tabla no está vacía, el feature depende de otro. Un cambio en esos eventos obliga a lanzar ambos specs juntos. -->

| Tipo | Feature dueño | Para qué |
|------|---------------|----------|
| Ninguno | | |

## 4. Proyecciones

<!--
Funciones puras: lista de eventos → resultado. Se implementan en Kotlin (:core / :feature)
y en Go (server/internal/<id>), y ambas deben pasar los fixtures de la sección 7.
-->

### P1. `<nombre>`
- **Entrada:** eventos `<tipo>`, `<tipo>`
- **Salida:** `{ campo: tipo, … }`
- **Reglas:** (referencia a 2.x cuando aplique)

## 5. Superficies

### Android
- **Pantallas:** …
- **Widget / notificaciones / servicios en background:** …
- **Permisos:** … (con justificación de una línea cada uno)

### Server
- **Endpoints:** Ninguno fuera del sync genérico, salvo: …
- **Workers:** `<nombre>` — qué eventos lee, qué eventos escribe, cada cuánto corre

### Web
- **Vistas:** …

## 6. Dependencias y efectos sobre otros features

<!-- Qué features consumen eventos de este. Si se cambia un evento de la sección 3, estos specs se lanzan juntos. -->

- Ninguno

## 7. Fixtures (tests de conformidad)

<!--
Un archivo por caso en schema/features/<id>/fixtures/<proyección>/<caso>.json
con forma { "events": [...], "expected": {...} }. Listarlos aquí es el criterio de aceptación.
El agente los escribe PRIMERO, luego hace pasar Kotlin y Go.
-->

| Proyección | Caso | Qué verifica |
|------------|------|--------------|
| P1 | `caso_01_<nombre>` | … |

## 8. Fuera de alcance y decisiones

<!-- Lo que deliberadamente NO hace, y decisiones con su porqué en una línea. Evita que el agente "mejore" cosas. -->

- …

## 9. Changelog

<!-- Una línea por versión. Qué cambió en comportamiento, no en código. -->

- `v1` — AAAA-MM-DD — creación.
