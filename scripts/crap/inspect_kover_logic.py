#!/usr/bin/env python3
import xml.etree.ElementTree as ET
from pathlib import Path

root = ET.parse(Path("app/build/reports/kover/reportDebug.xml")).getroot()
needles = ("QuickLaunchDragLogic", "TimerServiceLogic", "NegotiationManagerLogic", "OverlayNudgeLogic", "MainActivityLogic")
for pkg in root.findall("package"):
    for cls in pkg.findall("class"):
        name = cls.attrib.get("name", "")
        if not any(n in name for n in needles):
            continue
        print("CLASS", name, "src", cls.attrib.get("sourcefilename"))
        for method in cls.findall("method"):
            counters = {
                c.attrib["type"]: (int(c.attrib["missed"]), int(c.attrib["covered"]))
                for c in method.findall("counter")
            }
            instr = counters.get("INSTRUCTION", (0, 0))
            branch = counters.get("BRANCH", (0, 0))
            print(
                f"  {method.attrib.get('name')} line={method.attrib.get('line')} "
                f"instr={instr} branch={branch}"
            )
