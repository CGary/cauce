# android/

Vacío a propósito. Lo genera el agente en `BOOTSTRAP.md` fase 3.

Estructura objetivo:

```
app/              Application, FeatureRegistry, NavHost, wiring manual
core/events/      sobre, Room, EventStore, Projection + registro, test de conformidad
core/ui/          tema + ~10 componentes
core/sync/        WorkManager: push/pull y subida de blobs
feature/<id>/     un módulo Gradle por feature; nunca importa otro feature
```
