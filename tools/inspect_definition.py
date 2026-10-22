import sys,json,pathlib
sys.path.insert(0,'tools');from local_login import api
p=pathlib.Path('.private/read-only');t=json.loads((p/'process_pageUserTodoTask.json').read_text(encoding='utf-8'))['content'][0]
for path,q in [('workflow/getAssigneeElementsByProcessInstanceId',{'processInstanceId':t['processInstanceId']}),('workflow/getProcessInstanceById',{'processInstanceId':t['processInstanceId']}),('workflow/getFormPropertiesConditionsByTaskId',{'taskId':t['id']}),('faultType/getFaultTypeTree',{'enabled':True})]:
 try:
  d=api(path,query=q);(p/(path.replace('/','_')+'_current.json')).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8');print(path,'keys',list(d) if isinstance(d,dict) else list(d[0]) if d else [],'count',len(d) if isinstance(d,list)else '')
 except Exception as e:print(path,str(e))
