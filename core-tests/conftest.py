"""Put the packaged core on the path, without installing it.

The core lives where Chaquopy expects it - inside the Android module -
so the tests reach across rather than the other way round.
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "app/src/main/python"))
