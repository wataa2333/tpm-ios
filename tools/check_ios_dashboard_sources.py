"""Syntax-only checks; this does not replace Swift type checking or Xcode tests."""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path('build/source-check-deps').resolve()))
import tree_sitter
import tree_sitter_swift

paths = sorted(Path('ios').rglob('*.swift'))
parser = tree_sitter.Parser(tree_sitter.Language(tree_sitter_swift.language()))
errors = []
def configuration(text, debug):
    active = True
    stack = []
    lines = []
    for line in text.splitlines():
        directive = line.strip()
        if directive == '#if DEBUG':
            stack.append(active); active = active and debug; lines.append('')
        elif directive == '#else' and stack:
            active = stack[-1] and not active; lines.append('')
        elif directive == '#endif' and stack:
            active = stack.pop(); lines.append('')
        else:
            lines.append(line if active else '')
    return '\n'.join(lines)

for path, debug in [(path, debug) for path in paths for debug in (True, False)]:
    text = path.read_text(encoding='utf-8-sig')
    # The grammar treats a newline before a second trailing closure as a statement boundary.
    text = re.sub(r'}\s*\n\s*(label|icon):\s*{', r'} \1: {', text)
    # Syntax parser only: this Swift grammar crashes on some Swift Testing macro expansions.
    if 'TPMTests' in str(path):
        text = text.replace('@Test', '').replace('#expect(', 'expect(').replace('#require(', 'require(')
    text = configuration(text, debug)
    parsed = parser.parse(text.encode())
    root = parsed.root_node
    if not root.has_error:
        continue
    queue = [root]
    while queue:
        node = queue.pop()
        if node.type == 'ERROR' or node.is_missing:
            errors.append({'file': str(path), 'debug': debug, 'line': node.start_point.row + 1,
                           'kind': node.type, 'text': node.text.decode()[:160]})
        queue.extend(node.children)
result = {'files': len(paths), 'configurationPaths': len(paths) * 2, 'syntaxErrors': errors,
          'normalization': 'Trailing closure line breaks; DEBUG/Release branches; Testing macro names in memory only',
          'notRun': ['Swift typecheck', 'Xcode build', 'unit tests', 'UI tests', 'device acceptance']}
Path('build/ios-source-check.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
raise SystemExit(bool(errors))
