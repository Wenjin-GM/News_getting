import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
const root=path.resolve(import.meta.dirname,'../..');
const releaseVersion=process.argv[2]||'1.1.0';
const dataDir=path.join(root,'.cache','smoke-'+Date.now());
await fs.mkdir(dataDir,{recursive:true});
const proc=spawn(path.join(root,'.cache','windows-package',`NewsDesk-${releaseVersion}-windows-x64`,'NewsDesk.exe'),['-data',dataDir,'-port','0','-refresh=false'],{windowsHide:true,stdio:'ignore'});
const exited=new Promise(resolve=>proc.once('exit',resolve));
let runtime;
for(let i=0;i<60;i++){try{runtime=JSON.parse(await fs.readFile(path.join(dataDir,'runtime.json'),'utf8'));break;}catch{await new Promise(r=>setTimeout(r,100));}}
if(!runtime){proc.kill();throw Error('Fresh portable binary did not start');}
const base='http://127.0.0.1:'+runtime.port;
const headers={Authorization:'Bearer '+new URL(runtime.localUrl).hash.slice(7),'Content-Type':'application/json'};
const call=async(p,method='GET',body)=>{const r=await fetch(base+'/api/'+p,{method,headers,body:body?JSON.stringify(body):undefined});const j=await r.json();if(!r.ok)throw Error(p+': '+j.error);return j;};
try{
 const unauthorized=await fetch(base+'/api/state');if(unauthorized.status!==401)throw Error('Unauthorized access accepted');
 const first=await call('state');if(first.sources.length!==36||first.sources.filter(s=>s.enabled).length!==29)throw Error('Incorrect defaults');
 const catalog=await call('ai/catalog');if(catalog.providers.length!==16||catalog.tools.length!==31)throw Error('Incomplete AI catalog');
 await call('refresh','POST',{force:true,source:'',category:'general'});
 let result;
 for(let i=0;i<90;i++){result=await call('state');if(!result.run.running)break;await new Promise(r=>setTimeout(r,500));}
 if(result.run.running||result.run.success<8)throw Error('Too few successful fresh channels: '+JSON.stringify(result.run));
 if(result.run.total!==11||result.sources.filter(s=>s.source.category==='ai').some(s=>s.attempts!==0))throw Error('General refresh leaked into AI sources');
 const generalSuccess=result.run.success;
 await call('refresh','POST',{force:true,source:'',category:'ai'});
 for(let i=0;i<90;i++){result=await call('state');if(!result.run.running)break;await new Promise(r=>setTimeout(r,500));}
 if(result.run.running||result.run.total!==18||result.run.success<12)throw Error('AI refresh did not complete adequately: '+JSON.stringify(result.run));
 const aiSuccess=result.run.success,aiFailures=result.sources.filter(s=>s.source.category==='ai'&&s.status==='error').map(s=>s.source.id);
 const add=await call('feeds','POST',{name:'验收临时订阅',url:'https://www.chinanews.com.cn/rss/finance.xml',category:'finance'});
 await call('refresh','POST',{force:true,source:add.source.id});
 for(let i=0;i<30;i++){const s=await call('state');if(!s.run.running){const feed=s.sources.find(s=>s.source.id===add.source.id);if(!feed.items.length)throw Error('Custom feed returned no articles');break;}await new Promise(r=>setTimeout(r,500));}
 await call('feeds/'+add.source.id,'DELETE');
 const exported=await call('export','POST',{format:'json',scope:'current'});if(!exported.path.startsWith(dataDir))throw Error('Export escaped its data directory');
 const catalogExport=await call('export','POST',{format:'json',scope:'ai-catalog'});if(JSON.parse(catalogExport.content).tools.length!==31)throw Error('Incomplete AI catalog export');
 const itemCount=result.sources.filter(s=>s.enabled).reduce((sum,s)=>sum+s.items.length,0);
 console.log(JSON.stringify({portableFirstStart:'pass',auth:'pass',sources:result.sources.length,enabled:29,generalSuccess,aiSuccess,aiFailures,providers:catalog.providers.length,tools:catalog.tools.length,items:itemCount,customRSS:'pass',export:'pass',catalogExport:'pass',scopedRefresh:'pass'},null,2));
 await call('shutdown','POST',{});await exited;
}catch(e){proc.kill();throw e;}
