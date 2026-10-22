import sys,json,pathlib
sys.path.insert(0,'tools');from local_login import api
p=pathlib.Path('.private/read-only');tasks=json.loads((p/'process_pageUserTodoTask.json').read_text(encoding='utf-8'))['content']
for t in tasks:
 print('TASK_METADATA',json.dumps({k:t.get(k) for k in ['category','processDefinitionKey','taskDefinitionKey']},ensure_ascii=False))
 for path,q in [('workflow/getWorkflowTaskState',{'processInstanceId':t['processInstanceId']}),('workflow/getFormPropertiesConditionsByTaskId',{'taskId':t['id']}),('workflow/getWorkflowHistoryNodesByProcessInstanceId',{'processInstanceId':t['processInstanceId']})]:
  try:
   d=api(path,query=q);(p/(path.replace('/','_')+'_current.json')).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8');print(path,'KEYS',list(d) if isinstance(d,dict) else 'list', 'COUNT',len(d) if isinstance(d,list)else '')
   if isinstance(d,dict) and 'flowConditions'in d:print('CONDITIONS',json.dumps(d['flowConditions'],ensure_ascii=False),'FORMFIELDS',str(d.get('formFields'))[:1200])
  except Exception as e: print(path,type(e).__name__,str(e))
