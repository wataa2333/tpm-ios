"""Read-only audit of app-owned UI animation and UIKit interception paths.

The old migration added fades to dynamic labels. That behavior was removed in
0.7.42: keep native controls and their system-owned transitions instead.
"""
from pathlib import Path
import json
import re

PATTERNS = {
    "custom control style/layout": r":\s*(?:ButtonStyle|PrimitiveButtonStyle|LabeledContentStyle|DisclosureGroupStyle|Layout|Animatable|GeometryEffect)\b",
    "UIKit view bridge": r":\s*(?:UIViewRepresentable|UIViewControllerRepresentable)\b",
    "app animation": r"\bwithAnimation\s*\(|\.animation\s*\(|\.(?:interfaceMotion|animatedTextValue)\s*\(",
    "keyboard interception": r"\b(?:inputAccessoryView|reloadInputViews|keyboardWill\w+Notification|keyboardDid\w+Notification)\b",
    "frame animation driver": r"\b(?:CADisplayLink|TimelineView)\b|Timer\.(?:publish|scheduledTimer)",
}

def main():
    findings = []
    paths = sorted(Path('ios/TPM').glob('*.swift'))
    for path in paths:
        for number, line in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
            if line.lstrip().startswith('//'):
                continue
            for rule, pattern in PATTERNS.items():
                if re.search(pattern, line):
                    findings.append({'file': str(path), 'line': number, 'rule': rule, 'code': line.strip()})
    result = {'files': len(paths), 'findings': findings, 'readOnly': True,
              'limits': 'Source audit only; cannot measure frame rate, visual flicker or system control behavior.'}
    print(json.dumps(result, ensure_ascii=False))
    return bool(findings)

if __name__ == '__main__':
    raise SystemExit(main())
