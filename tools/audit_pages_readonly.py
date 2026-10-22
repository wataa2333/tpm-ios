"""Read-only schema and available task-operation checks. Never print credentials or names."""
import json
from pathlib import Path
from local_login import api

p = Path('.private/read-only')
checks = []
for state in ['UNTREATED', 'REPORTED', 'RECEIVED', 'SIGNED', 'REPAIRED', 'VERIFIED', 'RESOLVED', 'FINISHED']:
    try:
        result = api('fault/pageAssetFault', body={'offset': 0, 'limit': 2, 'sort': 'createTime', 'order': 'desc', 'filters': [{'field': 'faultState', 'value': state, 'operator': 'eq'}]})
        (p / ('fault_stage_' + state + '.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        rows = result.get('content', [])
        operations = []
        for row in rows[:1]:
            detail = api('fault/getAssetFaultDetailById', query={'faultId': row['faultId']})
            task = detail.get('taskInfo') or {}
            if task.get('id'):
                form = api('workflow/getFormPropertiesConditionsByTaskId', query={'taskId': task['id']})
                (p / ('fault_conditions_' + state + '.json')).write_text(json.dumps(form, ensure_ascii=False, indent=2), encoding='utf-8')
                operations = [{'name': x.get('name'), 'value': x.get('value')} for x in form.get('flowConditions', [])]
        checks.append({'state': state, 'sampleCount': len(rows), 'operations': operations})
    except Exception as exc:
        checks.append({'state': state, 'errorType': type(exc).__name__})
for endpoint in ['material/pageMaterialInfo', 'warehouse/pageWarehousing']:
    try:
        value = api(endpoint, body={'offset': 0, 'limit': 2, 'sort': 'createTime', 'order': 'desc', 'filters': []})
        (p / (endpoint.replace('/', '_') + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
        rows = value.get('content', [])
        print(endpoint, 'row keys', list(rows[0]) if rows else [], 'nested keys', {k: list(v) for k, v in (rows[0] if rows else {}).items() if isinstance(v, dict)})
    except Exception as exc:
        print(endpoint, type(exc).__name__)
(p / 'task-operation-audit.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(checks, ensure_ascii=False))
