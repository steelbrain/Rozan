"""Parser for the FairPlay v3 `garble` operation list in docs/protocol/04-fairplay.md §4.9.

The spec gives `garble` as 173 numbered statements in a C-like notation. This
module reads that list straight from the spec text and turns each statement
into a small expression tree, so code generators never transcribe it by hand.

Semantics (from §4.9): every value is a 32-bit unsigned integer; `+ - *` wrap,
`÷` and `%` are unsigned, `>>` is logical, buffer reads are bytes (0..255) and
buffer stores keep the low 8 bits. Operator precedence is C's.
"""

import re

BUFFERS = {"b0": 20, "b1": 210, "b2": 35, "b3": 132, "b4": 21}
FUNCTIONS = {"rol8": 2, "rol8x": 2, "weird_ror8": 2, "weird_rol8": 2, "weird_rol32": 2}
OP_COUNT = 173

# C binary operator precedence, higher binds tighter.
BINARY = {
    "*": 10, "÷": 10, "%": 10,
    "+": 9, "-": 9,
    "<<": 8, ">>": 8,
    "&": 5,
    "^": 4,
    "|": 3,
}

TOKEN = re.compile(r"\s*(0x[0-9a-fA-F]+|\d+|[A-Za-z_][A-Za-z_0-9]*|<<|>>|\+=|-=|\^=|[-+*÷%&|^~()\[\],=])")


class ParseError(Exception):
    pass


def tokenize(text):
    pos = 0
    out = []
    text = text.rstrip()
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m:
            raise ParseError("bad token at %r" % text[pos:])
        out.append(m.group(1))
        pos = m.end()
    return out


class Parser:
    def __init__(self, tokens):
        self.t = tokens
        self.i = 0

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else None

    def take(self, expected=None):
        tok = self.peek()
        if tok is None or (expected is not None and tok != expected):
            raise ParseError("expected %r, got %r" % (expected, tok))
        self.i += 1
        return tok

    def expression(self, min_prec=0):
        left = self.unary()
        while True:
            op = self.peek()
            prec = BINARY.get(op)
            if prec is None or prec < min_prec:
                return left
            self.take()
            right = self.expression(prec + 1)
            left = ("bin", op, left, right)

    def unary(self):
        tok = self.peek()
        if tok == "~":
            self.take()
            return ("not", self.unary())
        if tok == "-":
            self.take()
            return ("neg", self.unary())
        return self.primary()

    def primary(self):
        tok = self.take()
        if tok == "(":
            e = self.expression()
            self.take(")")
            return e
        if re.fullmatch(r"0x[0-9a-fA-F]+|\d+", tok):
            value = int(tok, 0)
            if value > 0xFFFFFFFF:
                raise ParseError("literal too wide: %s" % tok)
            return ("const", value, tok)
        if tok in BUFFERS:
            self.take("[")
            index = self.expression()
            self.take("]")
            check_index(tok, index)
            return ("read", tok, index)
        if tok in FUNCTIONS:
            self.take("(")
            args = [self.expression()]
            while self.peek() == ",":
                self.take()
                args.append(self.expression())
            self.take(")")
            if len(args) != FUNCTIONS[tok]:
                raise ParseError("%s takes %d arguments" % (tok, FUNCTIONS[tok]))
            return ("call", tok, args)
        if re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", tok):
            return ("var", tok)
        raise ParseError("unexpected %r" % tok)


def check_index(buffer, index):
    """Every buffer index must provably stay in bounds: a constant, or `x % n` with n <= length."""
    size = BUFFERS[buffer]
    if index[0] == "const" and index[1] < size:
        return
    if index[0] == "bin" and index[1] == "%" and index[3][0] == "const" and 0 < index[3][1] <= size:
        return
    raise ParseError("index into %s is not provably in bounds" % buffer)


def parse_statement(text):
    tokens = tokenize(text)
    p = Parser(tokens)
    first = p.take()
    if first in BUFFERS:
        p.take("[")
        index = p.expression()
        p.take("]")
        check_index(first, index)
        target = ("read", first, index)
    elif re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", first):
        target = ("var", first)
    else:
        raise ParseError("bad assignment target %r" % first)
    op = p.take()
    if op not in ("=", "+=", "-=", "^="):
        raise ParseError("bad assignment operator %r" % op)
    value = p.expression()
    if p.peek() is not None:
        raise ParseError("trailing tokens: %r" % tokens[p.i:])
    if target[0] == "var" and op != "=":
        raise ParseError("compound assignment to a scratch variable")
    return (target, op, value)


def read_ops(spec_path):
    """Returns [(number, source_text, statement)] for the 173 operations of §4.9."""
    with open(spec_path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    start = None
    for i, line in enumerate(lines):
        if line.startswith("The 173 operations"):
            start = i
            break
    if start is None:
        raise ParseError("cannot find the §4.9 operation list")
    while not lines[start].startswith("```"):
        start += 1
    ops = []
    for line in lines[start + 1:]:
        if line.startswith("```"):
            break
        m = re.fullmatch(r"\s*(\d+)\.\s+(.*?)\s*", line)
        if not m:
            raise ParseError("unexpected line in op list: %r" % line)
        number = int(m.group(1))
        if number != len(ops) + 1:
            raise ParseError("op %d out of sequence" % number)
        ops.append((number, m.group(2), parse_statement(m.group(2))))
    if len(ops) != OP_COUNT:
        raise ParseError("expected %d ops, found %d" % (OP_COUNT, len(ops)))
    return ops
