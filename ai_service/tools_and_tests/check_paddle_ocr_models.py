import os
import sys


REQUIRED_DIRS = [
    "ch_PP-OCRv4_det_infer",
    "ch_PP-OCRv4_rec_infer",
    "ch_ppocr_mobile_v2.0_cls_infer",
    "ch_ppstructure_mobile_v2.0_SLANet_infer",
    "picodet_lcnet_x1_0_fgd_layout_infer",
]

REQUIRED_FILES = [
    "inference.pdmodel",
    "inference.pdiparams",
]


def main() -> int:
    model_dir = os.getenv("PADDLE_OCR_MODEL_DIR", "/app/models/paddle_ocr")
    print(f"PADDLE_OCR_MODEL_DIR={model_dir}")

    missing = []
    for name in REQUIRED_DIRS:
        path = os.path.join(model_dir, name)
        if not os.path.isdir(path):
            missing.append(f"{name}/")
            continue
        for filename in REQUIRED_FILES:
            file_path = os.path.join(path, filename)
            if not os.path.isfile(file_path):
                missing.append(f"{name}/{filename}")

    if missing:
        print("PaddleOCR offline model check failed. Missing:")
        for item in missing:
            print(f"  - {item}")
        return 1

    print("PaddleOCR offline model check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
