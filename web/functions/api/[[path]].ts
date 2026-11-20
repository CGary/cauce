// /api/* → proxy de mismo origen con verificación de Cloudflare Access (ver ../_shared/proxy.ts).
// El destino real es la variable API_ORIGIN del proyecto de Pages, nunca el repo.
import { handleProxy } from '../_shared/proxy'

export const onRequest = handleProxy
