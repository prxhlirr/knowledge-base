"""
Compatibility wrapper for the production doc_meta_v2 backfill script.
"""

import os
import sys


SCRIPT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scripts"))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from backfill_doc_meta_v2 import run


if __name__ == "__main__":
    run()
