# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Tests for generate-update-plugins-xml.py"""

import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).parent / "generate-update-plugins-xml.py"


def run_script(version: str, zip_name: str, notes_content: str | None = None) -> subprocess.CompletedProcess:
    with tempfile.NamedTemporaryFile(mode="w", suffix=".md", delete=False) as f:
        if notes_content:
            f.write(notes_content)
        notes_path = f.name

    return subprocess.run(
        [sys.executable, str(SCRIPT), version, zip_name, notes_path],
        capture_output=True,
        text=True,
    )


def test_plugin_url_points_to_zip():
    """The plugin URL must point to a .zip file, not LICENSE or other assets."""
    result = run_script("1.0.0", "mcp-steroid-1.0.0.zip")
    assert result.returncode == 0, f"Script failed: {result.stderr}"

    root = ET.fromstring(result.stdout)
    plugin = root.find("plugin")
    url = plugin.get("url")
    assert url.endswith(".zip"), f"Plugin URL must end with .zip, got: {url}"
    assert url == "https://github.com/jonnyzzz/mcp-steroid/releases/download/1.0.0/mcp-steroid-1.0.0.zip"


def test_rejects_non_zip_filename():
    """Script must reject non-.zip filenames (e.g. LICENSE) with a clear error."""
    result = run_script("1.0.0", "LICENSE")
    assert result.returncode != 0, "Script should fail for non-.zip filename"
    assert "must end with .zip" in result.stderr


def test_version_in_xml_attributes():
    result = run_script("2.3.4", "mcp-steroid-2.3.4.zip")
    assert result.returncode == 0

    root = ET.fromstring(result.stdout)
    plugin = root.find("plugin")
    assert plugin.get("version") == "2.3.4"
    assert plugin.get("id") == "com.jonnyzzz.mcp-steroid"


def test_release_notes_included():
    notes = "## New Feature\n- **Bold** item\n- Plain item\n"
    result = run_script("1.0.0", "mcp-steroid-1.0.0.zip", notes)
    assert result.returncode == 0

    root = ET.fromstring(result.stdout)
    plugin = root.find("plugin")
    change_notes = plugin.find("change-notes").text
    assert "New Feature" in change_notes
    assert "<b>Bold</b>" in change_notes


def test_zip_name_with_commit_hash():
    """Real release zip names include a commit hash suffix."""
    result = run_script("0.89.0", "mcp-steroid-0.89.0-b8388824.zip")
    assert result.returncode == 0

    root = ET.fromstring(result.stdout)
    plugin = root.find("plugin")
    url = plugin.get("url")
    assert url == "https://github.com/jonnyzzz/mcp-steroid/releases/download/0.89.0/mcp-steroid-0.89.0-b8388824.zip"


if __name__ == "__main__":
    tests = [f for f in dir() if f.startswith("test_")]
    failed = []
    for name in tests:
        try:
            globals()[name]()
            print(f"  PASS  {name}")
        except AssertionError as e:
            print(f"  FAIL  {name}: {e}")
            failed.append(name)

    if failed:
        print(f"\n{len(failed)} test(s) failed")
        sys.exit(1)
    else:
        print(f"\nAll {len(tests)} tests passed")
