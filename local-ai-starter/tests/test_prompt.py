import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class PromptTests(unittest.TestCase):
    def test_structured_prompt(self):
        result = subprocess.run(
            [sys.executable, str(ROOT / "rawdob_ai.py"),
             "write", "a", "CSV", "parser"],
            text=True, capture_output=True, check=True
        )
        self.assertIn("Preferred language: Python", result.stdout)
        self.assertIn("CSV parser", result.stdout)
        self.assertIn("Never execute code", result.stdout)

if __name__ == "__main__":
    unittest.main()
