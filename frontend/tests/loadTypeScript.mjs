import { readFileSync } from 'node:fs'
import ts from 'typescript'

const modules = new Map()

export function typeScriptModuleUrl(path, parent = import.meta.url) {
  const url = new URL(path, parent)
  if (modules.has(url.href)) return modules.get(url.href)
  const source = readFileSync(url, 'utf8')
  let script = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 }
  }).outputText
  script = script.replace(/from (['"])([^'"]+)\1/g, (_, quote, specifier) => {
    const target = specifier.startsWith('.')
      ? typeScriptModuleUrl(specifier.endsWith('.ts') ? specifier : specifier + '.ts', url)
      : import.meta.resolve(specifier)
    return 'from ' + JSON.stringify(target)
  })
  const moduleUrl = 'data:text/javascript;base64,' + Buffer.from(script).toString('base64')
  modules.set(url.href, moduleUrl)
  return moduleUrl
}

export function loadTypeScript(path, parent = import.meta.url) {
  return import(typeScriptModuleUrl(path, parent))
}
