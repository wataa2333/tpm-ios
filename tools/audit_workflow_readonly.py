"""Inspect existing workflow metadata; never submit or modify production documents."""
import json
from pathlib import Path
from local_login import api

out = Path('.private/read-only')
for category in ['ASSET_FAULT', 'ERP_OUTBOUND']:
    for endpoint, query in [
        ('process/getTaskCategoriesByProcessCategory', {'processCategory': category}),
        ('process/getProcessDefinitionByCategory', {'processCategory': category}),
    ]:
        try:
            value = api(endpoint, query=query)
            (out / (endpoint.replace('/', '_') + '_' + category + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
            print(category, endpoint, 'keys', list(value) if isinstance(value, dict) else 'list', 'count', len(value) if isinstance(value, list) else '')
        except Exception as exc:
            print(category, endpoint, type(exc).__name__)

    definitions = json.loads((out / ('process_getProcessDefinitionByCategory_' + category + '.json')).read_text(encoding='utf-8'))
    for index, definition in enumerate(definitions):
        for endpoint in ['workflow/getProcessDefinitionById', 'workflow/getProcessDefinitionDiagram', 'workflow/getAssigneeElementsByDefinitionId']:
            try:
                query_key = 'processDefinitionId' if endpoint.endswith('getAssigneeElementsByDefinitionId') else 'id'
                value = api(endpoint, query={query_key: definition['id']})
                (out / (endpoint.replace('/', '_') + '_' + category + '_' + str(index) + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
                print(category, index, endpoint, 'keys', list(value) if isinstance(value, dict) else type(value).__name__)
            except Exception as exc:
                print(category, index, endpoint, type(exc).__name__)
