#!/usr/bin/env python3
"""Generates Garble.kt from the `garble` operation list in docs/protocol/04-fairplay.md §4.9.

Usage (from the repository root):

    python3 airplay/fairplay/tools/gen_garble.py docs/protocol/04-fairplay.md \
        > airplay/fairplay/src/main/kotlin/ai/aneesiqbal/rozan/airplay/fairplay/Garble.kt

The 173 statements are parsed from the spec text (garble_ops.py) and emitted as
Kotlin over `UInt`, whose `+ - * / %`, `shr` and `inv()` have exactly the
32-bit unsigned semantics §4.9 asks for. Every expression is side-effect free,
so a statement too long for one line has its largest subexpressions hoisted into
local `val`s first; the result is the same value.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import garble_ops  # noqa: E402

WIDTH = 110  # content width inside a method body (8-space indent, 120-column limit)
CHUNK_LINES = 60  # keeps each generated method under detekt's LongMethod limit

KOTLIN_FUNCTIONS = {
    "rol8": "rol8",
    "rol8x": "rol8x",
    "weird_ror8": "weirdRor8",
    "weird_rol8": "weirdRol8",
    "weird_rol32": "weirdRol32",
}
KOTLIN_INFIX = {"<<": "shl", ">>": "shr", "&": "and", "^": "xor", "|": "or"}
KOTLIN_ARITH = {"*": ("*", 3), "÷": ("/", 3), "%": ("%", 3), "+": ("+", 2), "-": ("-", 2)}
ATOM, MUL, ADD, INFIX = 4, 3, 2, 1


def var_name(name):
    return name.lower()


def render(e):
    """Returns (text, precedence level) for an expression tree."""
    kind = e[0]
    if kind == "const":
        return e[2] + "u", ATOM
    if kind == "var":
        return var_name(e[1]), ATOM
    if kind == "hoisted":
        return e[1], ATOM
    if kind == "read":
        return "%s[%s]" % (e[1], render(e[2])[0]), ATOM
    if kind == "not":
        return "%s.inv()" % paren(e[1], ATOM), ATOM
    if kind == "neg":
        return "0u - %s" % paren(e[1], ADD + 1), ADD
    if kind == "call":
        return "%s(%s)" % (KOTLIN_FUNCTIONS[e[1]], ", ".join(render(a)[0] for a in e[2])), ATOM
    if kind == "bin":
        op, left, right = e[1], e[2], e[3]
        if op in ("<<", ">>"):
            count = right[2] if right[0] == "const" else "%s.toInt()" % paren(right, ATOM)
            return "%s %s %s" % (paren(left, INFIX, same=op), KOTLIN_INFIX[op], count), INFIX
        if op in KOTLIN_INFIX:
            return "%s %s %s" % (paren(left, INFIX, same=op), KOTLIN_INFIX[op], paren(right, INFIX)), INFIX
        text, level = KOTLIN_ARITH[op]
        return "%s %s %s" % (paren(left, level), text, paren(right, level + 1)), level
    raise ValueError(e)


def paren(e, min_level, same=None):
    """Renders `e`, parenthesised unless it binds at least as tightly as `min_level`.

    Operands of Kotlin's infix functions (`and`, `or`, `shl`, ...) are always
    parenthesised unless they are atoms or continue a left-associative chain of
    the same operator: the infix functions share one precedence level below
    arithmetic, which reads differently from C, so explicit grouping keeps the
    generated code comparable with the spec.
    """
    text, level = render(e)
    if min_level == INFIX:
        chained = same is not None and e[0] == "bin" and e[1] == same
        return text if level == ATOM or chained else "(%s)" % text
    return text if level >= min_level else "(%s)" % text


def subtrees(e):
    yield e
    if e[0] in ("not", "neg"):
        yield from subtrees(e[1])
    elif e[0] == "read":
        yield from subtrees(e[2])
    elif e[0] == "call":
        for a in e[2]:
            yield from subtrees(a)
    elif e[0] == "bin":
        yield from subtrees(e[2])
        yield from subtrees(e[3])


def replace(e, old, new):
    if e == old:
        return new
    if e[0] in ("not", "neg"):
        return (e[0], replace(e[1], old, new))
    if e[0] == "read":
        return ("read", e[1], replace(e[2], old, new))
    if e[0] == "call":
        return ("call", e[1], [replace(a, old, new) for a in e[2]])
    if e[0] == "bin":
        return ("bin", e[1], replace(e[2], old, new), replace(e[3], old, new))
    return e


def statement_text(target, op, value):
    if target[0] == "var":
        return "%s = %s" % (var_name(target[1]), render(value)[0])
    lhs = render(target)[0]
    if op == "=":
        return "%s = %s" % (lhs, render(value)[0])
    if op == "^=":
        return "%s = %s xor %s" % (lhs, lhs, paren(value, INFIX))
    return "%s %s %s" % (lhs, op, render(value)[0])


def emit_op(number, statement):
    """Returns the Kotlin lines for one operation, hoisting subexpressions until every line fits."""
    target, op, value = statement
    hoisted = []
    while len(statement_text(target, op, value)) > WIDTH:
        name = "o%d%s" % (number, chr(ord("a") + len(hoisted)))
        prefix = len("val %s = " % name)
        candidates = [
            s for s in subtrees(value)
            if s[0] not in ("const", "var", "hoisted") and s != value and prefix + len(render(s)[0]) <= WIDTH
        ]
        best = max(candidates, key=lambda s: len(render(s)[0]))
        hoisted.append("val %s = %s" % (name, render(best)[0]))
        node = ("hoisted", name)
        value = replace(value, best, node)
        target = replace(target, best, node) if target[0] == "read" else target
    original_target = statement[0]
    if original_target[0] == "read" and original_target[2][0] != "const":
        raise garble_ops.ParseError("op %d: trace needs a constant store index" % number)
    trace = "trace?.written(%d, %s)" % (number, render(original_target)[0])
    return ["// %d" % number] + hoisted + [statement_text(target, op, value), trace]


def main():
    ops = garble_ops.read_ops(sys.argv[1])
    variables = sorted({var_name(t[1]) for _, _, (t, _, _) in ops if t[0] == "var"})

    chunks = []
    current = []
    for number, _, statement in ops:
        lines = emit_op(number, statement)
        if current and sum(len(c[1]) for c in current) + len(lines) > CHUNK_LINES:
            chunks.append(current)
            current = []
        current.append((number, lines))
    chunks.append(current)

    out = []
    w = out.append
    w("// Generated by airplay/fairplay/tools/gen_garble.py from docs/protocol/04-fairplay.md section 4.9.")
    w("// Do not edit by hand: change the generator or the spec and regenerate.")
    w("package ai.aneesiqbal.rozan.airplay.fairplay")
    w("")
    w("/**")
    w(" * The `garble` step of `sap_hash` (spec section 4.9): a fixed sequence of %d" % garble_ops.OP_COUNT)
    w(" * operations over the five `sap_hash` buffers, rewritten in place.")
    w(" *")
    w(" * Every value is a 32-bit unsigned integer ([UInt]): `+ - *` wrap, `/` and `%`")
    w(" * are unsigned, `shr` is logical. Buffer reads yield 0..255 and buffer stores")
    w(" * keep the low 8 bits. Each `// n` comment is the operation number in the spec.")
    w(" * After each operation, [trace] (tests only) receives the value it wrote.")
    w(" */")
    w("internal class Garble(")
    for name, size in garble_ops.BUFFERS.items():
        w("    /** %d bytes. */" % size)
        w("    private val %s: ByteArray," % name)
    w("    private val trace: GarbleTrace? = null,")
    w(") {")
    for v in variables:
        w("    private var %s = 0u" % v)
    w("")
    w("    /** Runs all %d operations in order. */" % garble_ops.OP_COUNT)
    w("    fun run() {")
    names = []
    for chunk in chunks:
        names.append("ops%dTo%d" % (chunk[0][0], chunk[-1][0]))
    for n in names:
        w("        %s()" % n)
    w("    }")
    for name, chunk in zip(names, chunks):
        w("")
        w("    private fun %s() {" % name)
        for _, lines in chunk:
            for line in lines:
                w("        " + line)
        w("    }")
    w("}")
    w("")
    w("/** Observes [Garble] one operation at a time; tests compare it with the spec's step trace. */")
    w("internal fun interface GarbleTrace {")
    w("    /** Operation [op] (numbered as in the spec) wrote [value]: a byte, or a 32-bit scratch variable. */")
    w("    fun written(")
    w("        op: Int,")
    w("        value: UInt,")
    w("    )")
    w("}")
    w("")
    w("private operator fun ByteArray.get(index: UInt): UInt = (this[index.toInt()].toInt() and 0xFF).toUInt()")
    w("")
    w("private operator fun ByteArray.set(")
    w("    index: UInt,")
    w("    value: UInt,")
    w(") {")
    w("    this[index.toInt()] = value.toByte()")
    w("}")
    w("")
    w("/** 8-bit left rotate of the low byte of [v]; 8-bit result. */")
    w("private fun rol8(")
    w("    v: UInt,")
    w("    c: UInt,")
    w("): UInt = ((v shl c.toInt()) and 0xFFu) or ((v and 0xFFu) shr (8 - c.toInt()))")
    w("")
    w("/** Like [rol8] but the left-shifted part is not masked to 8 bits. */")
    w("private fun rol8x(")
    w("    v: UInt,")
    w("    c: UInt,")
    w("): UInt = (v shl c.toInt()) or ((v and 0xFFu) shr (8 - c.toInt()))")
    w("")
    w("/** 8-bit right rotate with a 32-bit result; 0 when [c] is 0. */")
    w("private fun weirdRor8(")
    w("    v: UInt,")
    w("    c: UInt,")
    w("): UInt = if (c == 0u) 0u else ((v shr c.toInt()) and 0xFFu) or ((v and 0xFFu) shl (8 - c.toInt()))")
    w("")
    w("/** 8-bit left rotate with an 8-bit result; 0 when [c] is 0. */")
    w("private fun weirdRol8(")
    w("    v: UInt,")
    w("    c: UInt,")
    w("): UInt = if (c == 0u) 0u else ((v shl c.toInt()) and 0xFFu) or ((v and 0xFFu) shr (8 - c.toInt()))")
    w("")
    w("/** `(v << c) ^ (v >> (8 - c))` on 32 bits; 0 when [c] is 0. */")
    w("private fun weirdRol32(")
    w("    v: UInt,")
    w("    c: UInt,")
    w("): UInt = if (c == 0u) 0u else (v shl c.toInt()) xor (v shr (8 - c.toInt()))")
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
