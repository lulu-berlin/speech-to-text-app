import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// The shape rule for the docs/api examples (ADR 0029, docs/api/README.md): a response
// matches a shape when it has the same keys, the same JSON value types, and `null` only
// where an example of that shape has `null`. Several files can form one shape
// (task.queued.json…task.canceled.json); together they define it: a field is nullable
// if `null` appears for it in any variant, and its value type is the union of the
// variants' non-null types.
const CONTRACT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'docs', 'api');

const cache: Record<string, Shape> = Object.create(null);

type Shape = {
  // JSON kinds this node may take, excluding null (handled by `nullable`).
  kinds: Set<string>;
  // object shape: field name -> field shape; an observed extra or missing key is drift.
  fields?: Record<string, Shape>;
  element?: Shape;
  nullable: boolean;
};

function kindsOf(value: unknown): string[] {
  if (value === null) return [];
  if (Array.isArray(value)) return ['array'];
  return [typeof value];
}

function mergeInto(target: Shape, extra: Shape, seen: Set<object>): void {
  for (const kind of extra.kinds) target.kinds.add(kind);
  target.nullable ||= extra.nullable;
  if (extra.element !== undefined) {
    if (target.element === undefined) target.element = { kinds: new Set(), nullable: false };
    mergeInto(target.element, extra.element, seen);
    return;
  }
  if (extra.fields === undefined || target.fields === undefined) return;
  for (const [key, child] of Object.entries(extra.fields)) {
    if (child.kinds.has('object') || child.kinds.has('array')) {
      if (seen.has(child)) continue; // recursion guard
    }
    if (target.fields[key] === undefined) {
      const fresh: Shape = { kinds: new Set(), nullable: false };
      target.fields[key] = fresh;
    }
    const freshSeen = new Set(seen);
    mergeInto(target.fields[key], child, freshSeen);
  }
}

// Builds the declared shape of one variant.
function variantToShape(value: unknown, seen: Set<object>): Shape {
  if (value === null) return { kinds: new Set(), nullable: true };
  const shape: Shape = { kinds: new Set(kindsOf(value)), nullable: false };
  if (Array.isArray(value)) {
    shape.element = variantToShape(value[0] ?? null, seen);
  } else if (typeof value === 'object' && value !== null) {
    if (seen.has(value)) return shape;
    seen.add(value);
    shape.fields = Object.create(null);
    for (const [key, child] of Object.entries(value)) {
      shape.fields[key] = variantToShape(child, seen);
    }
  }
  return shape;
}

export function shapeOf(names: string[]): Shape {
  const key = names.join('|');
  if (cache[key] !== undefined) return cache[key];

  const merged: Shape = { kinds: new Set(), fields: Object.create(null), nullable: false };
  for (const variant of loadVariants(names)) {
    const variantShape = variantToShape(variant, new Set());
    mergeInto(merged, variantShape, new Set());
  }
  cache[key] = merged;
  return merged;
}

function loadVariants(names: string[]): unknown[] {
  return names.map((name) => JSON.parse(readFileSync(join(CONTRACT_DIR, `${name}.json`), 'utf8')));
}

function describe(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
}

export function shapeProblems(names: string[], observed: unknown, path = '$'): string[] {
  const shape = shapeOf(names);
  const problems: string[] = [];

  const check = (shape: Shape, value: unknown, path: string) => {
    if (value === null) {
      if (!shape.nullable)
        problems.push(`${path}: null but no ${names.join('/')} example has null`);
      return;
    }
    const kind = describe(value);
    if (!shape.kinds.has(kind)) {
      problems.push(`${path}: expected ${[...shape.kinds].join('|')}, got ${kind}`);
      return;
    }
    if (kind === 'object') {
      if (shape.fields === undefined) return;
      for (const key of Object.keys(value as object)) {
        if (!Object.hasOwn(shape.fields, key)) {
          problems.push(`${path}.${key}: not in the ${names.join('/')} example`);
        }
      }
      for (const [key, fieldShape] of Object.entries(shape.fields)) {
        if (Object.hasOwn(value as object, key)) {
          check(fieldShape, (value as Record<string, unknown>)[key], `${path}.${key}`);
        } else {
          problems.push(`${path}.${key}: missing`);
        }
      }
    }
    if (kind === 'array') {
      // The examples fix the element shape; the length is state, not contract. A shape
      // without a fixed element (an example array of `null`) accepts any element.
      if (shape.element === undefined) return;
      for (const [index, element] of (value as unknown[]).entries()) {
        check(shape.element, element, `${path}[${index}]`);
      }
    }
  };

  check(shape, observed, path);
  return problems;
}

// Asserts [observed] conforms to the union of the example files [names].
export function mustConform(names: string[], observed: unknown): void {
  const problems = shapeProblems(names, observed);
  if (problems.length > 0) {
    throw new Error(
      `contract drift vs docs/api/${names.join(', ')}.json:\n  ${problems.join('\n  ')}`,
    );
  }
}
