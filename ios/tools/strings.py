#!/usr/bin/env python3
"""Volta for iOS: the String Catalog (Localization/Localizable.xcstrings), with the same
wording as the Android app.

    python3 ios/tools/strings.py           writes the catalog
    python3 ios/tools/strings.py --check   fails when the catalog is out of date, a key used
                                           in Swift is missing, a language lacks a text, or
                                           the %-placeholders of a translation differ

Sources, in this order:
  1. Android's string resources (app/src/main/res/values*/strings*.xml): every key Swift
     uses by its Android name (L("routing_title")), and every iOS key whose Russian text is
     the same as an Android string, take Android's text in all 15 languages.
  2. strings_table.py: texts only the iPhone has (10 languages), and strings_extra.py: the
     same in the other 5, plus newer iOS-only keys in all 15.

Keys used in Swift: L("key"), L10n.tr("key"), L10n.plural("key", n), and any string literal
that is an Android key (Presets, RoutingNames, error keys). Plurals become plural variations
of the catalog, so iOS picks the right form in every language (Arabic's six included).
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
IOS = os.path.dirname(HERE)
RES = os.path.join(os.path.dirname(IOS), "app", "src", "main", "res")
CATALOG = os.path.join(IOS, "Localization", "Localizable.xcstrings")
sys.path.insert(0, HERE)
from strings_table import LANGS as TABLE_LANGS, TABLE  # noqa: E402
from strings_extra import EXTRA, NEW_LANGS  # noqa: E402

LANGS = ["ru", "en", "uk", "be", "kk", "uz", "az", "tr", "fa", "ar", "zh-Hans", "es", "de", "fr", "pt-BR"]
ANDROID_DIRS = {
    "values": "en", "values-ru": "ru", "values-uk": "uk", "values-be": "be", "values-kk": "kk",
    "values-uz": "uz", "values-az": "az", "values-tr": "tr", "values-fa": "fa", "values-ar": "ar",
    "values-zh-rCN": "zh-Hans", "values-es": "es", "values-de": "de", "values-fr": "fr", "values-pt-rBR": "pt-BR",
}
PLURAL_SUFFIX = re.compile(r"\.(zero|one|two|few|many|other)$")
KEY_USE = re.compile(r'(?:L10n\.tr|L10n\.plural|\bL)\(\s*"([A-Za-z0-9_.]+)"')
PLURAL_USE = re.compile(r'L10n\.plural\(\s*"([A-Za-z0-9_.]+)"')
LITERAL = re.compile(r'"([a-z][a-z0-9_]+)"')
SPEC = re.compile(r"%(?:\d+\$)?[-+ 0#]*\d*(?:\.\d+)?(?:ll|l|h)?([@dDuUxXoOfeEgGcCsSpaAF])")
# What a translation must carry the same of: text and number arguments ("100 %" is no argument).
ARG = re.compile(r"%(?:\d+\$)?l{0,2}([@d])")


# ------------------------------------------------------------------ Android

def android_text(el):
    t = "".join(el.itertext())
    t = t.strip()
    if len(t) >= 2 and t.startswith('"') and t.endswith('"'):
        t = t[1:-1]
    t = (t.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"')
         .replace("\\@", "@").replace("\\?", "?").replace("\\t", "\t"))
    return t


def to_ios(text, plural=False):
    """Android placeholders into Foundation's: %1$s → %1$@, %d → %ld."""
    def fix(m):
        whole = m.group(0)
        conv = m.group(1)
        # "100 % here" is a per cent sign, not an argument.
        if " " in whole or conv not in "sd":
            return whole
        if conv == "s":
            whole = whole[:-1] + "@"
        elif conv == "d":
            whole = whole[:-1].rstrip("l") + "ld"
        return whole
    out = SPEC.sub(fix, text.replace("%%", "\0")).replace("\0", "%%")
    if plural and len(SPEC.findall(out)) == 1:
        out = re.sub(r"%1\$", "%", out)
    return out


def load_android():
    strings, plurals = {}, {}
    for folder, lang in ANDROID_DIRS.items():
        for path in sorted(glob.glob(os.path.join(RES, folder, "strings*.xml"))):
            for el in ET.parse(path).getroot():
                name = el.get("name")
                if el.tag == "string":
                    strings.setdefault(name, {})[lang] = to_ios(android_text(el))
                elif el.tag == "plurals":
                    forms = {i.get("quantity"): to_ios(android_text(i), plural=True) for i in el if i.tag == "item"}
                    plurals.setdefault(name, {})[lang] = forms
                elif el.tag == "string-array":
                    for n, item in enumerate(i for i in el if i.tag == "item"):
                        strings.setdefault(f"{name}.{n}", {})[lang] = to_ios(android_text(item))
    return strings, plurals


# ------------------------------------------------------------------ Swift

def swift_files():
    for root, dirs, files in os.walk(IOS):
        dirs[:] = [d for d in dirs if d not in (".build", "build", "Frameworks", "Volta.xcodeproj", "tools", "Tests")]
        for f in files:
            if f.endswith(".swift"):
                yield os.path.join(root, f)


def used(android_keys):
    keys, plurals = set(), set()
    for path in swift_files():
        text = open(path, encoding="utf-8").read()
        keys.update(k for k in KEY_USE.findall(text) if "." in k or "_" in k)
        plurals.update(PLURAL_USE.findall(text))
        keys.update(k for k in LITERAL.findall(text) if k in android_keys)
    # Months on the traffic card are looked up by number.
    keys.update(k for k in android_keys if k.startswith("home_months."))
    return keys - plurals, plurals


def norm(s):
    return re.sub(r"%\d+\$", "%", s).replace("%ld", "%d").replace("%lld", "%d")


# ------------------------------------------------------------------ catalog

def unit(value):
    return {"stringUnit": {"state": "translated", "value": value}}


def build():
    a_strings, a_plurals = load_android()
    keys, plurals = used(set(a_strings) | set(a_plurals))
    by_ru = {}
    for k, langs in a_strings.items():
        if "ru" in langs:
            by_ru.setdefault(norm(langs["ru"]), k)

    out, problems = {}, []
    table_plurals = {PLURAL_SUFFIX.sub("", k) for k in TABLE if PLURAL_SUFFIX.search(k)}

    for key in sorted(keys):
        texts = {}
        if key in a_strings:
            texts = dict(a_strings[key])
        elif key in TABLE or key in EXTRA:
            entry = dict(TABLE.get(key, {}))
            entry.update(EXTRA.get(key, {}))
            twin = by_ru.get(norm(entry.get("ru", "")))
            texts = dict(entry)
            if twin:
                texts.update(a_strings[twin])
        else:
            problems.append(f"missing key: {key}")
            continue
        loc = {}
        for lang in LANGS:
            value = texts.get(lang) or texts.get("en")
            if value is None:
                problems.append(f"{key}: no {lang}")
                continue
            if lang not in texts:
                problems.append(f"{key}: no {lang} (English used)")
            loc[lang] = unit(value)
        base = sorted(ARG.findall(texts.get("ru", "")))
        for lang in LANGS:
            if lang in texts and sorted(ARG.findall(texts[lang])) != base:
                problems.append(f"{key} [{lang}]: placeholders differ from Russian")
        out[key] = {"extractionState": "manual", "localizations": loc}

    for key in sorted(plurals):
        forms_by_lang = {}
        if key in a_plurals:
            forms_by_lang = a_plurals[key]
        elif key in table_plurals or key in EXTRA:
            for lang in TABLE_LANGS:
                forms = {}
                for q in ("zero", "one", "two", "few", "many", "other"):
                    e = TABLE.get(f"{key}.{q}")
                    if e and lang in e:
                        forms[q] = to_ios(e[lang], plural=True)
                if forms:
                    forms_by_lang[lang] = forms
            for lang, forms in EXTRA.get(key, {}).items():
                forms_by_lang[lang] = {q: to_ios(v, plural=True) for q, v in forms.items()}
        else:
            problems.append(f"missing plural: {key}")
            continue
        loc = {}
        for lang in LANGS:
            forms = forms_by_lang.get(lang) or forms_by_lang.get("en")
            if not forms or "other" not in forms:
                problems.append(f"plural {key}: no {lang}")
                continue
            if lang not in forms_by_lang:
                problems.append(f"plural {key}: no {lang} (English used)")
            loc[lang] = {"variations": {"plural": {q: unit(v) for q, v in forms.items()}}}
        out[key] = {"extractionState": "manual", "localizations": loc}

    catalog = {"sourceLanguage": "ru", "strings": out, "version": "1.0"}
    return catalog, problems


def render(catalog):
    return json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main():
    catalog, problems = build()
    text = render(catalog)
    if "--check" in sys.argv:
        current = open(CATALOG, encoding="utf-8").read() if os.path.exists(CATALOG) else ""
        if current != text:
            problems.append("Localizable.xcstrings is out of date: run python3 ios/tools/strings.py")
    else:
        os.makedirs(os.path.dirname(CATALOG), exist_ok=True)
        with open(CATALOG, "w", encoding="utf-8") as f:
            f.write(text)
        # The old per-language tables would clash with the catalog.
        for old in glob.glob(os.path.join(IOS, "Localization", "*.lproj")):
            for f in os.listdir(old):
                os.remove(os.path.join(old, f))
            os.rmdir(old)
        print(f"wrote {len(catalog['strings'])} keys in {len(LANGS)} languages")
    if problems:
        for p in problems:
            print("::error::" + p if os.environ.get("GITHUB_ACTIONS") else p)
        print(f"{len(problems)} problem(s)")
        return 1
    print(f"ok: {len(catalog['strings'])} keys, {len(LANGS)} languages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
