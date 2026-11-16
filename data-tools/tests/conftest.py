import sys
from pathlib import Path

# Make `rp_data` importable when pytest runs from data-tools/ or the repo root.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
