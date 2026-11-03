"""Finds Kotlin string literals, including ${...} templates with nested strings."""
import re

AR = re.compile('[؀-ۿ]')
BACKSLASH = chr(92)


def literals(src):
    """Returns (start, end, text) for every regular "..." literal outside comments."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            i = src.find('\n', i)
            i = n if i < 0 else i
            continue
        if src.startswith('/*', i):
            i = src.find('*/', i) + 2
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == BACKSLASH else 1
            i = j + 1
            continue
        if src.startswith('"""', i):
            i = src.find('"""', i + 3) + 3
            continue
        if c == '"':
            end = skip_string(src, i)
            out.append((i, end, src[i + 1:end - 1]))
            i = end
            continue
        i += 1
    return out


def skip_string(src, i):
    j = i + 1
    while True:
        c = src[j]
        if c == BACKSLASH:
            j += 2
            continue
        if c == '"':
            return j + 1
        if src.startswith('${', j):
            j = skip_template(src, j + 2)
            continue
        j += 1


def skip_template(src, j):
    depth = 1
    while depth:
        c = src[j]
        if c == '"':
            j = skip_string(src, j)
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        j += 1
    return j
