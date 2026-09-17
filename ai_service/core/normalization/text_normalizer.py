import re
import unicodedata

_ENCODED_CHINESE_RE = re.compile(r"(?i)(?:%E[0-9A-F](?:%[0-9A-F]{2}){2})")
_MOSTLY_PERCENT_BYTES_RE = re.compile(r"(?i)^(?:%[0-9A-F]{2}|[._\-\+\s])+$")
_WHITESPACE_RE = re.compile(r"\s+")
_UNSAFE_FILENAME_RE = re.compile(r'[\\/:*?"<>|]')


def has_encoded_chinese(value) -> bool:
    return bool(value and _ENCODED_CHINESE_RE.search(str(value)))


def normalize_filename(value, limit: int = 200) -> str:
    text = _decode_when_useful(_to_text(value), passes=2)
    text = unicodedata.normalize("NFC", text)
    text = _UNSAFE_FILENAME_RE.sub("_", text)
    return text[:limit]


def normalize_metadata_text(value) -> str:
    text = _decode_when_useful(_to_text(value), passes=2)
    text = unicodedata.normalize("NFC", text)
    return _WHITESPACE_RE.sub(" ", text).strip()


def normalize_content_if_fully_encoded(value) -> str:
    text = _to_text(value)
    if not _looks_fully_encoded(text):
        return text
    return _decode_when_useful(text, passes=2)


def normalize_list(values, limit: int = 500) -> list:
    if values is None:
        return []
    source = values if isinstance(values, (list, tuple, set)) else [values]
    result = []
    for item in source:
        text = normalize_metadata_text(item)
        if text and text not in result:
            result.append(text)
        if len(result) >= limit:
            break
    return result


def _decode_when_useful(value: str, passes: int) -> str:
    if not has_encoded_chinese(value) and "%25" not in value:
        return value
    best = value
    current = value
    for _ in range(passes):
        decoded = _percent_decode_path_segment(current)
        if decoded == current:
            break
        if _cjk_count(decoded) > _cjk_count(best):
            best = decoded
        current = decoded
    return best


def _looks_fully_encoded(value: str) -> bool:
    return bool(value and has_encoded_chinese(value) and _MOSTLY_PERCENT_BYTES_RE.match(value))


def _percent_decode_path_segment(value: str) -> str:
    output = []
    buf = bytearray()
    i = 0
    while i < len(value):
        ch = value[i]
        if ch == "%" and i + 2 < len(value) and _is_hex(value[i + 1:i + 3]):
            buf.append(int(value[i + 1:i + 3], 16))
            i += 3
            continue
        if buf:
            output.append(buf.decode("utf-8", errors="replace"))
            buf.clear()
        output.append(ch)
        i += 1
    if buf:
        output.append(buf.decode("utf-8", errors="replace"))
    return "".join(output)


def _is_hex(value: str) -> bool:
    return len(value) == 2 and all(ch in "0123456789abcdefABCDEF" for ch in value)


def _cjk_count(value: str) -> int:
    return sum(1 for ch in value if "\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf")


def _to_text(value) -> str:
    return "" if value is None else str(value).strip()
