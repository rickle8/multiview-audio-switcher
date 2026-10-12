"""Print build/test errors as GitHub annotations so they show on the run page."""
import glob
import re
import xml.etree.ElementTree as ET

try:
    for line in open("build.log", errors="replace"):
        if re.match(r"^e: |.*error:", line):
            print(f"::error::{line.strip()[:400]}")
except FileNotFoundError:
    pass

for f in glob.glob("android/app/build/test-results/testDebugUnitTest/*.xml"):
    for tc in ET.parse(f).getroot().iter("testcase"):
        for fl in tc.findall("failure") + tc.findall("error"):
            msg = (fl.get("message") or fl.text or "").strip().splitlines()
            print(f"::error::TEST {tc.get('name')}: {(msg[0] if msg else '')[:400]}")
