import sys,json,pathlib,concurrent.futures
sys.path.insert(0,'tools');from local_login import api
p=pathlib.Path('.private/read-only')
def read(n):return json.loads((p/(n+'.json')).read_text(encoding='utf-8'))
fault=read('fault_pageAssetFault')['content'][0];outbound=read('erpOutbound_pageErpOutbound')['content'][0]
entries=[('erp/getAllDivisionByFactoryId',None,None),('fault/getAssetFaultDetailById',{'faultId':fault['faultId']},None),('fault/getLatestRepairByFaultId',{'faultId':fault['faultId']},None),('erpOutbound/getErpOutboundById',{'outboundId':outbound['outboundId']},None),('erpOutbound/getErpMaterialOutboundsByOutboundId',{'outboundId':outbound['outboundId']},None),('process/pageUserTodoTask',None,{'offset':0,'limit':5,'filters':[]})]
for kind,item in [('fault',fault),('erp',outbound)]:
 if item.get('processInstanceId'):
  for path in ['workflow/getWorkflowHistoryNodesByProcessInstanceId','workflow/getWorkflowTaskState']:
   entries.append((path,{'processInstanceId':item['processInstanceId']},None))
def run(entry):
 path,q,body=entry
 try:
  d=api(path,query=q,body=body);name=path.replace('/','_')+('_'+str(q.get('processInstanceId')) if q and q.get('processInstanceId') else '')
  (p/(name+'.json')).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
  rows=d.get('content',[]) if isinstance(d,dict) else d
  return {'endpoint':path,'keys':list(d) if isinstance(d,dict) else None,'count':len(rows) if isinstance(rows,list) else None,'rowKeys':list(rows[0]) if isinstance(rows,list) and rows and isinstance(rows[0],dict) else None}
 except Exception as e:return {'endpoint':path,'error':str(e)}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 for r in pool.map(run,entries):print(json.dumps(r,ensure_ascii=False))
