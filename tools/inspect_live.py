import sys,json,pathlib,concurrent.futures
sys.path.insert(0,'tools');from local_login import api,load_session
out=pathlib.Path('.private/read-only');out.mkdir(parents=True,exist_ok=True)
auth=load_session();print('AUTH_KEYS',list(auth));print('FACTORY_AVAILABLE',bool(auth.get('factoryId')))
pages=['fault/pageAssetFault','erpOutbound/pageErpOutbound','erpOutbound/pageErpMaterial','asset/pageAsset']
gets=[('user/getUserDeptByUserId',{'userId':auth['id']}),('user/getEnabledUsers',None),('user/getEnabledUserGroupsByGroupType',{'groupType':'REPAIR'}),('dept/getDeptSimpleTree',None),('division/getAllDivisionByFactoryId',None),('erpOutbound/getProjectByCompanyCode',None),('workflow/getAssigneeElementsByProcessCategory',{'processCategory':'ASSET_FAULT'}),('workflow/getAssigneeElementsByProcessCategory',{'processCategory':'ERP_OUTBOUND'})]
def run(entry):
 path,q,body=entry
 try:
  data=api(path,query=q,body=body)
  name=path.replace('/','_')+('_'+q.get('processCategory','') if q and 'processCategory'in q else '')
  (out/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
  rows=data.get('content',[]) if isinstance(data,dict) else data
  return {'endpoint':path,'keys':list(data) if isinstance(data,dict) else None,'count':len(rows) if isinstance(rows,list) else None,'rowKeys':list(rows[0]) if isinstance(rows,list) and rows and isinstance(rows[0],dict) else None}
 except Exception as e:return {'endpoint':path,'error':str(e)}
entries=[(p,None,{'offset':0,'limit':5,'sort':'createTime','order':'desc','filters':[]}) for p in pages]+[(p,q,None) for p,q in gets]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 for result in pool.map(run,entries):print(json.dumps(result,ensure_ascii=False))
