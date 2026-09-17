from core.normalization.text_normalizer import (
    has_encoded_chinese,
    normalize_content_if_fully_encoded,
    normalize_filename,
    normalize_metadata_text,
)


def test_filename_decodes_chinese_percent_encoding_without_treating_plus_as_space():
    assert normalize_filename("%E6%B0%91%E6%B3%95%E5%85%B8+A.docx") == "民法典+A.docx"


def test_metadata_decodes_chinese_percent_encoding():
    assert normalize_metadata_text("%E8%A1%8C%E6%94%BF%E5%A4%84%E7%BD%9A%E5%86%B3%E5%AE%9A%E4%B9%A6") == "行政处罚决定书"


def test_metadata_preserves_normal_percent_text():
    assert normalize_metadata_text("完成率 95%") == "完成率 95%"


def test_content_only_decodes_when_whole_text_is_encoded():
    assert normalize_content_if_fully_encoded(
        "%E5%85%B3%E4%BA%8E%E6%B0%91%E6%B3%95%E5%85%B8%E7%9A%84%E9%80%9A%E7%9F%A5"
    ) == "关于民法典的通知"
    assert normalize_content_if_fully_encoded(
        "参考链接 https://example.test/%E6%B0%91%E6%B3%95%E5%85%B8"
    ) == "参考链接 https://example.test/%E6%B0%91%E6%B3%95%E5%85%B8"


def test_detects_encoded_chinese():
    assert has_encoded_chinese("%E6%B0%91%E6%B3%95")
