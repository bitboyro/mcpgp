// Arguments to start trying a tool with (Tools & rules → Try): the required ones, filled from the input
// schema (its examples, defaults and enums, else a value of the right type); `instance` is the mock.

type Schema = { type?: string | string[]; properties?: Record<string, Schema>; required?: string[]; enum?: unknown[]; default?: unknown;
  examples?: unknown[]; example?: unknown; const?: unknown; items?: Schema; format?: string; minimum?: number }

export function exampleValue(schema: Schema | undefined, name = '', depth = 0): unknown {
  if (!schema) return ''
  if (name === 'instance') return 'mock'
  if (schema.const !== undefined) return schema.const
  if (schema.examples?.length) return schema.examples[0]
  if (schema.example !== undefined) return schema.example
  if (schema.default !== undefined) return schema.default
  if (schema.enum?.length) return schema.enum[0]
  const type = Array.isArray(schema.type) ? schema.type.find((t) => t !== 'null') : schema.type ?? (schema.properties ? 'object' : 'string')
  switch (type) {
    case 'object': {
      const out: Record<string, unknown> = {}
      if (depth < 6) for (const key of schema.required ?? []) out[key] = exampleValue(schema.properties?.[key], key, depth + 1)
      return out
    }
    case 'array': return depth < 6 ? [exampleValue(schema.items, '', depth + 1)] : []
    case 'integer': return schema.minimum ?? 1
    case 'number': return schema.minimum ?? 1
    case 'boolean': return true
    default:
      return schema.format === 'date-time' ? '2026-01-01T00:00:00Z' : schema.format === 'date' ? '2026-01-01' : schema.format === 'email' ? 'someone@example.com' : 'example'
  }
}

/** The arguments a try starts with: the required ones (and `instance: mock`). */
export function startingArguments(inputSchema: unknown): Record<string, unknown> {
  const schema = (inputSchema ?? {}) as Schema
  const value = exampleValue({ ...schema, type: 'object' }) as Record<string, unknown>
  if (schema.properties?.instance && !('instance' in value)) value.instance = 'mock'
  return value
}
