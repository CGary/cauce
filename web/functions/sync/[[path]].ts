// /sync/* usa el mismo proxy verificado que /api/* (ver ../_shared/proxy.ts).
import { handleProxy } from '../_shared/proxy'

export const onRequest = handleProxy
