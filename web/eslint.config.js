import path from 'node:path'
import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

const SRC = path.resolve('src')

// Límites de módulos (los mismos que Gradle impone en Android):
// features/<id>/ solo importa de core/ (o de sí mismo); core/ nunca importa de features/.
const boundaries = {
  meta: { type: 'problem', schema: [] },
  create(context) {
    const file = context.filename
    const rel = path.relative(SRC, file).split(path.sep)
    const area = rel[0]
    const featureId = area === 'features' && rel.length > 2 ? rel[1] : undefined
    // Los tests pueden usar el arnés compartido (src/test, src/mocks).
    const harness = /\.test\.tsx?$/.test(file) ? ['test', 'mocks'] : []
    return {
      ImportDeclaration(node) {
        const spec = node.source.value
        if (typeof spec !== 'string' || !spec.startsWith('.')) return
        const target = path.relative(SRC, path.resolve(path.dirname(file), spec)).split(path.sep)
        if (target[0] === '..') return // fuera de src/ (p. ej. fixtures en ../schema)
        if (area === 'core' && target[0] === 'features') {
          context.report({ node, message: 'core/ no importa de features/.' })
        }
        const allowed = target[0] === 'core' || harness.includes(target[0] ?? '')
        if (featureId && !allowed && !(target[0] === 'features' && target[1] === featureId)) {
          context.report({ node, message: `features/${featureId}/ solo importa de core/.` })
        }
      },
    }
  },
}

export default tseslint.config(
  { ignores: ['dist', 'mocks-public', 'test-results', 'playwright-report'] },
  ...tseslint.configs.recommended,
  reactHooks.configs.flat['recommended-latest'],
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { cauce: { rules: { boundaries } } },
    rules: { 'cauce/boundaries': 'error' },
  },
)
