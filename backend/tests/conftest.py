import sys
from pathlib import Path

# Permite `import synth` (generador de canciones de prueba) y `import moimoi`.
TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS))
sys.path.insert(0, str(TESTS.parent))
