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
- **Variable de entorno:** `VITE_API_BASE=https://api.<tu-dominio>`. Se lee en tiempo de build, así que cambiarla exige redeploy.
- **Rutas:** sin `404.html`, Pages sirve `index.html` en cualquier ruta, que es lo que necesita React Router.
- **CLI:** también se puede desplegar a mano con `VITE_API_BASE=<origen de la API> npm run build` (sin barra final) y, desde la raíz del repo, `wrangler pages deploy web/dist --project-name <proyecto>`.
- **`_redirects`:** `public/_redirects` (`/*    /index.html   200`) es el fallback de SPA; Vite lo copia a `dist/`.
- **CORS:** si la web vive en otro origen que la API, el servidor debe permitirlo con `CAUCE_CORS_ORIGIN` (ver `server/.env.example`), con el origen de la web.
- **Acceso:** Cloudflare Access (login con Google) delante del sitio y de la API. Las peticiones salen con `credentials: 'include'` para que la cookie de Access viaje aunque la API esté en otro origen.

## Agregar un feature

1. Crear `src/features/<id>/` con `types.ts` (la forma de salida de cada proyección, copiada campo por campo de la sección 4 del SPEC), `api.ts`, `views/` y `mocks.ts`.
2. Exportar un `FeatureModule` desde `src/features/<id>/index.ts`, con una ruta por vista y cada vista cargada con `React.lazy`.
3. Agregarlo a `FEATURES` en `src/features/index.ts` y sus handlers a `src/mocks/handlers.ts`.

Un feature solo importa de `core/`; `core/` nunca importa de `features/`. Lo controla la regla ESLint `cauce/boundaries`.
