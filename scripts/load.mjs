// No dependencies. Run only against a disposable local queue: this creates/cancels tickets.
import {performance} from 'node:perf_hooks';
const base=process.env.BASE_URL || 'http://localhost:8080';
const concurrency=Number(process.env.CONCURRENCY || 4);
const rounds=Number(process.env.ROUNDS || 10);
if(!Number.isInteger(concurrency)||concurrency<1||concurrency>50||!Number.isInteger(rounds)||rounds<1||rounds>1000) throw new Error('Use CONCURRENCY 1–50 and ROUNDS 1–1000');
const samples=[];let errors=0, completed=0;
async function worker(){
  for(let i=0;i<rounds;i++){
    let cookie='', csrf='';
    async function call(path,init={}){
      const start=performance.now();
      const response=await fetch(base+'/api'+path,{...init,headers:{Cookie:cookie,'X-CSRF-TOKEN':csrf,'Content-Type':'application/json',...init.headers},signal:AbortSignal.timeout(10000)});
      const set=response.headers.getSetCookie();if(set.length) cookie=set.map(c=>c.split(';')[0]).join('; ');
      samples.push(performance.now()-start);
      if(!response.ok) throw new Error('HTTP '+response.status);
      return response.json();
    }
    try{
      csrf=(await call('/session')).csrfToken;
      const key=crypto.randomUUID();
      const options={method:'POST',headers:{'Idempotency-Key':key},body:JSON.stringify({displayName:'Load test'})};
      const first=await call('/student/join',options);
      const retry=await call('/student/join',options);
      if(first.ticket.id!==retry.ticket.id) throw new Error('Retry changed ticket');
      await call('/student');await call('/student/leave',{method:'POST'});completed++;
    }catch(error){errors++;console.error('Scenario failed:',error.message);}
  }
}
const start=performance.now();await Promise.all(Array.from({length:concurrency},worker));
samples.sort((a,b)=>a-b);
const percentile=p=>samples.length?samples[Math.min(samples.length-1,Math.floor(samples.length*p))].toFixed(2):null;
console.log(JSON.stringify({workload:'session, join, same-key retry, snapshot, leave',concurrency,rounds,completed,errors,requestsMeasured:samples.length,elapsedSeconds:(performance.now()-start)/1000,p50Ms:percentile(.5),p95Ms:percentile(.95),p99Ms:percentile(.99)},null,2));
process.exitCode=errors?1:0;
