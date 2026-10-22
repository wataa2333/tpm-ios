import json
from pathlib import Path
from local_login import api

p = Path('.private/read-only')
for category in ['ASSET_FAULT', 'ERP_OUTBOUND']:
    definitions = json.loads((p / ('process_getProcessDefinitionByCategory_' + category + '.json')).read_text(encoding='utf-8'))
    for index, definition in enumerate(definitions):
        try:
            value = api('process/getModelInfoByKey', query={'key': definition['key']})
            (p / ('model_' + category + '_' + str(index) + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
            print(category, index, 'model keys', list(value) if isinstance(value, dict) else type(value).__name__)
        except Exception as exc:
            print(category, index, type(exc).__name__)
