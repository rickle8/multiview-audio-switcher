#!/usr/bin/env python3
"""
Compiles every Regex(...) in the app's Kotlin code with ICU, the regex engine Android
uses on the phone. The unit tests run on the desktop Java regex engine, which accepts
some patterns Android refuses (for example a set starting with "[:", which ICU reads
as a "[:Letter:]" class). A refused pattern crashes the app the moment it's first used,
so this fails the build instead.

Usage: check_regex_android.py <source dir>
"""
import ctypes
import ctypes.util
import glob
import os
import re
import sys


def load_icu():
    names = sorted(glob.glob("/usr/lib/x86_64-linux-gnu/libicui18n.so.*") +
                   glob.glob("/usr/lib/aarch64-linux-gnu/libicui18n.so.*") +
                   glob.glob("/usr/lib/libicui18n.so.*"), key=len)
    found = ctypes.util.find_library("icui18n")
    if found:
        names.insert(0, found)
    for n in names:
        try:
            lib = ctypes.CDLL(n)
        except OSError:
            continue
        m = re.search(r"\.so\.(\d+)", n)
        candidates = ["uregex_open"] + ([f"uregex_open_{m.group(1)}"] if m else [])
        for v in range(80, 49, -1):
            candidates.append(f"uregex_open_{v}")
        for fn in candidates:
            if hasattr(lib, fn):
                close = getattr(lib, fn.replace("open", "close"))
                return getattr(lib, fn), close, n
    return None, None, None


def kotlin_strings(expr):
    """The string a Kotlin expression of literals joined with + adds up to (templates become 'x')."""
    out = []
    i = 0
    while i < len(expr):
        if expr.startswith('"""', i):
            j = expr.index('"""', i + 3)
            while expr.startswith('"', j + 3):  # """ followed by more quotes ends at the last three
                j += 1
            out.append(expr[i + 3:j])
            i = j + 3
        elif expr[i] == '"':
            j = i + 1
            buf = []
            while expr[j] != '"':
                c = expr[j]
                if c == "\\":
                    n = expr[j + 1]
                    buf.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"',
                                "'": "'", "$": "$"}.get(n, "\\" + n))
                    j += 2
                    continue
                if c == "$" and j + 1 < len(expr) and (expr[j + 1].isalpha() or expr[j + 1] == "{"):
                    if expr[j + 1] == "{":
                        j = expr.index("}", j) + 1
                    else:
                        j += 1
                        while j < len(expr) and (expr[j].isalnum() or expr[j] == "_"):
                            j += 1
                    buf.append("x")
                    continue
                buf.append(c)
                j += 1
            out.append("".join(buf))
            i = j + 1
        else:
            i += 1
    return "".join(out)


def literal_args(src, start):
    """The first argument of a call whose '(' is at [start], if it's made only of string literals."""
    depth = 0
    i = start
    in_str = None
    while i < len(src):
        if in_str:
            if in_str == '"""' and src.startswith('"""', i):
                in_str = None
                i += 3
                continue
            if in_str == '"' and src[i] == "\\":
                i += 2
                continue
            if in_str == '"' and src[i] == '"':
                in_str = None
            i += 1
            continue
        if src.startswith('"""', i):
            in_str = '"""'
            i += 3
            continue
        c = src[i]
        if c == '"':
            in_str = '"'
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
        elif c == "," and depth == 1:
            return src[start + 1:i]
        i += 1
    return None


def patterns(root):
    for path in sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True)):
        src = open(path, encoding="utf-8").read()
        for m in re.finditer(r"\bRegex\s*\(", src):
            arg = literal_args(src, m.end() - 1)
            if arg is None:
                continue
            stripped = re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"', "", arg)
            if re.sub(r"[\s+]", "", stripped):
                continue  # built from variables: can't check here
            line = src.count("\n", 0, m.start()) + 1
            yield f"{os.path.relpath(path, root)}:{line}", kotlin_strings(arg)


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    uregex_open, uregex_close, lib = load_icu()
    ci = os.environ.get("GITHUB_ACTIONS") == "true"
    if uregex_open is None:
        print(("::warning::" if ci else "") + "ICU not found: skipping the Android regex check")
        return 0
    uregex_open.restype = ctypes.c_void_p
    uregex_close.argtypes = [ctypes.c_void_p]
    bad = 0
    count = 0
    for where, pat in patterns(root):
        count += 1
        data = pat.encode("utf-16-le")
        buf = ctypes.create_string_buffer(data + b"\0\0")
        status = ctypes.c_int(0)
        perr = ctypes.create_string_buffer(64 + 2 * 16 * 2)
        r = uregex_open(buf, ctypes.c_int32(len(data) // 2), ctypes.c_uint32(0), perr, ctypes.byref(status))
        if status.value > 0:
            bad += 1
            print(("::error::" if ci else "") + f"Android can't compile the pattern at {where} (ICU error {status.value}): {pat}")
        if r:
            uregex_close(r)
    print(("::notice::" if ci else "") + f"Checked {count} patterns with {os.path.basename(lib)}: {bad} Android would refuse")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
