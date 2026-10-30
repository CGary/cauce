# web/

Consola de administración de Cauce: la misma información que la app, pero para administrar. Muestra las proyecciones que calcula el servidor (`GET /api/projections/{feature}/{nombre}`) y, cuando exista sync (ROADMAP 3), también escribirá eventos por `POST /sync/push`. En el navegador no hay lógica de proyecciones.

Stack: Vite + React 19 + TypeScript, TanStack Query, React Router, Tailwind v4. Tests con Vitest + Testing Library + MSW, y un test de humo con Playwright. Todas las versiones están fijadas en `package.json`. El detalle y las reglas están en `BOOTSTRAP.md` fase 4 y en `AGENTS.md`.

## Requisitos

Node 20.19 o superior (CI usa Node 20).

```sh
cd web
npm ci
cp .env.example .env   # opcional en desarrollo
```

## Correr

| Comando            | Qué hace                                                                                                            |
| ------------------ | ------------------------------------------------------------------------------------------------------------------- |
| `npm run dev`      | Servidor de desarrollo. `/api` se redirige por proxy a `http://localhost:8080`, el servidor Go (`just server-run`). |
| `npm run dev:mock` | La UI contra MSW, sin servidor Go. Los datos son los valores `expected` de `schema/features/<id>/fixtures`.         |

## Variables

| Variable        | Dónde                                                            | Valor                                                                                                   |
| --------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `VITE_API_BASE` | `.env` (ignorado por git) o la configuración de Cloudflare Pages | Origen del servidor Go, sin barra final. Vacío significa mismo origen: en desarrollo, el proxy de Vite. |

`.env.example` documenta la variable. `.env.mock` solo activa MSW en `--mode mock` y no tiene secretos.

## Testear

```sh
just test-web          # desde la raíz: tsc --noEmit && eslint . && vitest run
npm run format:check   # Prettier
npm run e2e:install    # una vez: chromium dentro de node_modules (no en el sistema)
npm run e2e            # test de humo; guarda e2e/screenshots/alarmas.png
```

Los tests de componentes y el de humo corren contra MSW, así que no necesitan el servidor Go.

## Compilar

```sh
just build-web   # o: npm run build → tsc --noEmit && vite build
```

La salida queda en `dist/`. MSW y su service worker no entran al build.

## Desplegar en Cloudflare Pages

- **Build command:** `npm run build`
- **Build output directory:** `dist`
- **Root directory:** `web`
- **`VITE_API_BASE`:** debe estar **vacía** en el build de Pages (así las peticiones son relativas, `/api/...` y `/sync/push`, al mismo origen).
- **`API_ORIGIN`:** variable de entorno del proyecto de Pages (runtime, nunca en el repo) con el origen real de la API, p. ej. `https://api.example.com` (sin barra final). La Pages Function `functions/api/[[path]].ts` (y `functions/sync/[[path]].ts`, que la reutiliza) reenvía GET/POST/OPTIONS de `/api/*` y `/sync/*` a ese origen, copiando método, cabeceras (sin las hop-by-hop), cuerpo y estado, y devolviendo la respuesta en streaming. Así el navegador nunca llama al host de la API: no hay CORS ni bloqueo de «acceso a red local» para quien resuelva ese host a una dirección privada (p. ej. con una VPN activa). Cambiar `API_ORIGIN` no exige rebuild, pero sí un nuevo deploy.
- **Rutas:** sin `404.html`, Pages sirve `index.html` en cualquier ruta, que es lo que necesita React Router.
- **CLI:** también se puede desplegar a mano con `npm run build` (con `VITE_API_BASE` vacía) y, desde la raíz del repo, `wrangler pages deploy web/dist --project-name <proyecto>`.
- **`_redirects`:** `public/_redirects` (`/*    /index.html   200`) es el fallback de SPA; Vite lo copia a `dist/`.
- **CORS:** con el proxy de mismo origen no hace falta. Solo si alguien llama a la API desde otro origen, el servidor debe permitirlo con `CAUCE_CORS_ORIGIN` (ver `server/.env.example`), con el origen de la web.
- **Acceso:** Cloudflare Access (login con Google) delante del sitio y de la API. Las peticiones salen con `credentials: 'include'`; con el proxy de mismo origen la cookie de Access del sitio viaja con ellas.

## Agregar un feature

1. Crear `src/features/<id>/` con `types.ts` (la forma de salida de cada proyección, copiada campo por campo de la sección 4 del SPEC), `api.ts`, `views/` y `mocks.ts`.
2. Exportar un `FeatureModule` desde `src/features/<id>/index.ts`, con una ruta por vista y cada vista cargada con `React.lazy`.
3. Agregarlo a `FEATURES` en `src/features/index.ts` y sus handlers a `src/mocks/handlers.ts`.

Un feature solo importa de `core/`; `core/` nunca importa de `features/`. Lo controla la regla ESLint `cauce/boundaries`.
