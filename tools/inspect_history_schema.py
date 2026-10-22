import json,pathlib
p=pathlib.Path('.private/read-only')
history=json.loads((p/'workflow_getWorkflowHistoryNodesByProcessInstanceId_current.json').read_text(encoding='utf-8'))
print('HISTORY_UNION_KEYS',sorted({k for n in history for k in n}))
print('COMMENT_KEYS',sorted({k for n in history for c in n.get('comments',[]) for k in c}))
