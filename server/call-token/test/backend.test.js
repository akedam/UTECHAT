"use strict";
const test=require("node:test"), assert=require("node:assert/strict"), http=require("node:http");
const {createBackend}=require("../backend"), {createStorage}=require("../supabase-storage");
const TIME=1_800_000_000_000;
const PNG=Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=","base64");
function fakeDb(initial) {
  const rows=new Map(Object.entries(initial).map(([key,value])=>[key,structuredClone(value)]));
  const doc=(path)=>({path,id:path.split("/").at(-1),get:async()=>snapshot(path),
    collection:(name)=>collection(`${path}/${name}`),
    update:async(data)=>rows.set(path,{...rows.get(path),...structuredClone(data)}),
    delete:async()=>rows.delete(path)});
  const snapshot=(path)=>({id:path.split("/").at(-1),ref:doc(path),exists:rows.has(path),data:()=>structuredClone(rows.get(path))});
  function collection(path,filter=null,max=Infinity) {
    return {doc:(id)=>doc(`${path}/${id}`),where:(key,operator,value)=>collection(path,[key,value],max),
      limit:(count)=>collection(path,filter,count),get:async()=>({docs:[...rows.keys()]
        .filter((key)=>key.startsWith(path+"/") && key.split("/").length===path.split("/").length+1)
        .filter((key)=>!filter || rows.get(key)[filter[0]]===filter[1]).slice(0,max).map(snapshot)})};
  }
  let pending=Promise.resolve();
  return {rows,doc,collection,runTransaction(fn){
    const task=pending.then(async()=>{
      const writes=[];
      const value=await fn({get:(ref)=>ref.get(),set:(ref,data)=>writes.push(()=>rows.set(ref.path,structuredClone(data))),
        update:(ref,data)=>writes.push(()=>rows.set(ref.path,{...rows.get(ref.path),...structuredClone(data)}))});
      writes.forEach((write)=>write());return value;
    });pending=task.catch(()=>{});return task;
  }};
}
async function fixture(t,options={}) {
  const db=fakeDb({
    "conversations/chat":{type:"direct",participantIds:["alice","bob"],lastMessageAt:TIME-5000},
    "conversations/chat/members/alice":{userId:"alice"},"conversations/chat/members/bob":{userId:"bob"},
    "publicProfiles/alice":{displayName:"Alice"},
    "users/bob/deviceTokens/device":{token:"bob-device-token"},
    "calls/call1":{callerUid:"alice",calleeUid:"bob",participants:["alice","bob"],conversationId:"chat",
      channelName:"call1",mediaType:"audio",status:"accepted",createdAt:TIME-1000,updatedAt:TIME-1000,expiresAt:TIME+45000},
    "conversations/chat/messages/msg1":{senderId:"alice",senderName:"Alice",type:"text",content:"Hello",sentAt:TIME},
    ...(options.rows||{}),
  });
  const pushes=[],objects=new Map(); let tokenCalls=0;
  const backend=createBackend({db,now:()=>TIME,
    auth:{async verifyIdToken(token){if(!["alice","bob","mallory"].includes(token))throw Error();return{uid:token};}},
    messaging:{async sendEachForMulticast(data){pushes.push(data);return{responses:data.tokens.map(()=>({success:true}))};}},
    mintToken(call,uid){tokenCalls++;return{token:"test-rtc-token",uid:call.rtcUidByUser[uid]};},
    storage:{async upload(path,bytes){if(objects.has(path))return false;objects.set(path,Buffer.from(bytes));return true;},
      async download(path){return objects.get(path);}},...options.backend,
  });
  const server=http.createServer(backend.handle);await new Promise((resolve)=>server.listen(0,"127.0.0.1",resolve));
  t.after(()=>{backend.close();server.closeAllConnections();return new Promise((resolve)=>server.close(resolve));});
  async function request(route,uid="alice",body={},method="POST",type="application/json") {
    const headers={"Content-Type":type};if(uid)headers.Authorization=`Bearer ${uid}`;
    const result=await fetch(`http://127.0.0.1:${server.address().port}${route}`,{method,headers,
      body:method==="GET"?undefined:Buffer.isBuffer(body)?body:JSON.stringify(body)});
    const bytes=Buffer.from(await result.arrayBuffer());
    return{status:result.status,body:result.headers.get("content-type").includes("application/json")?JSON.parse(bytes):bytes};
  }
  return{db,backend,pushes,objects,request,get tokenCalls(){return tokenCalls;}};
}
test("token authorization rejects absent/revoked/outsider credentials; RTC UIDs stay stable",async(t)=>{
  const f=await fixture(t);
  for(const [uid,status]of [[null,401],["revoked",401],["mallory",403]])
    assert.equal((await f.request("/api/rtc-token",uid,{callId:"call1"})).status,status);
  const alice=await f.request("/api/rtc-token","alice",{callId:"call1"});
  const bob=await f.request("/api/rtc-token","bob",{callId:"call1"});
  assert.equal(alice.status,200);assert.notEqual(alice.body.uid,bob.body.uid);
  assert.equal((await f.request("/api/rtc-token","alice",{callId:"call1"})).body.uid,alice.body.uid);
  assert.equal(f.tokenCalls,3);
  await f.db.doc("calls/call1").update({status:"ended"});
  assert.equal((await f.request("/api/rtc-token","alice",{callId:"call1"})).status,409);
});
test("membership removal prevents token issuance even for a listed call participant",async(t)=>{
  const f=await fixture(t);await f.db.doc("conversations/chat").update({participantIds:["alice","mallory"]});
  assert.equal((await f.request("/api/rtc-token","bob",{callId:"call1"})).status,403);
});
test("push uses stored message and actual members, cannot spoof a sender, and deduplicates retries",async(t)=>{
  const f=await fixture(t);
  assert.equal((await f.request("/api/message-notify","bob",{conversationId:"chat",messageId:"msg1"})).status,403);
  assert.equal((await f.request("/api/message-notify","alice",{conversationId:"chat",messageId:"msg1",preview:"fake"})).status,200);
  assert.equal((await f.request("/api/message-notify","alice",{conversationId:"chat",messageId:"msg1"})).status,200);
  assert.equal(f.pushes.length,1);assert.equal(f.pushes[0].data.preview,"Hello");assert.equal(f.pushes[0].data.recipientUid,"bob");
});
test("only caller sends an invitation and expired calls cannot ring",async(t)=>{
  const f=await fixture(t);await f.db.doc("calls/call1").update({status:"ringing"});
  assert.equal((await f.request("/api/call-invite","bob",{callId:"call1"})).status,403);
  assert.equal((await f.request("/api/call-invite","alice",{callId:"call1"})).status,200);
  assert.equal(f.pushes[0].data.eventType,"call");
  await f.db.doc("calls/call1").update({expiresAt:TIME-1});
  assert.equal((await f.request("/api/call-invite","alice",{callId:"call1"})).status,409);
});
test("expiry is atomic and idempotent; accepted calls are preserved",async(t)=>{
  const f=await fixture(t);
  await f.backend.expireCall("call1");assert.equal(f.db.rows.get("calls/call1").status,"accepted");
  await f.db.doc("calls/call1").update({status:"ringing",expiresAt:TIME-1,mediaType:"video"});
  await Promise.all([f.backend.expireCall("call1"),f.backend.expireCall("call1")]);
  assert.equal(f.db.rows.get("calls/call1").status,"missed");
  assert.equal(f.db.rows.get("conversations/chat/messages/call_call1").content,"📹 Cuộc gọi video nhỡ");
  assert.equal([...f.db.rows.keys()].filter((key)=>key.includes("messages/call_")).length,1);
});
test("private images require owner upload, actual message and current membership for download",async(t)=>{
  const f=await fixture(t),path="conversations/chat/alice/photo1",route="/api/media?path="+encodeURIComponent(path);
  assert.equal((await f.request(route,"bob",PNG,"PUT","image/png")).status,403);
  const uploaded=await f.request(route,"alice",PNG,"PUT","image/png");assert.equal(uploaded.status,200);
  assert.equal((await f.request(route,"alice",PNG,"PUT","image/png")).status,200);
  assert.equal(f.objects.size,1);
  assert.equal((await f.request(route,"bob",{},"GET")).status,403);
  f.db.rows.set("conversations/chat/messages/photo1",{type:"image",senderId:"alice",mediaPath:path});
  assert.deepEqual((await f.request(route,"bob",{},"GET")).body,PNG);
  await f.db.doc("conversations/chat").update({participantIds:["alice","mallory"]});
  assert.equal((await f.request(route,"bob",{},"GET")).status,403);
  const changed=Buffer.from(PNG);changed[25]^=1;
  assert.equal((await f.request(route,"alice",changed,"PUT","image/png")).status,409);
});
test("uploads reject traversal, fake images and over-quota writes",async(t)=>{
  const f=await fixture(t,{backend:{dailyUploadBytes:PNG.length}});
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/..","alice",PNG,"PUT","image/png")).status,400);
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/fake","alice",Buffer.from("not an image file"),"PUT","image/png")).status,400);
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/one","alice",PNG,"PUT","image/png")).status,200);
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/two","alice",PNG,"PUT","image/png")).status,429);
});
test("health contains no keys and optional unconfigured media fails explicitly",async(t)=>{
  const f=await fixture(t,{backend:{storage:null}});
  assert.deepEqual((await f.request("/healthz",null,{},"GET")).body,{status:"configured",apiVersion:1,media:false});
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/x","alice",PNG,"PUT","image/png")).status,503);
});
test("Supabase adapter uses private authenticated access and immutable uploads",async()=>{
  const calls=[];
  const storage=createStorage({url:"https://testing.supabase.co",key:"sb_secret_testing",fetchImpl:async(url,init)=>{
    calls.push({url,init});return init.method==="POST"?new Response(JSON.stringify({error:"Duplicate"}),{status:400}):new Response(PNG);
  }});
  assert.equal(await storage.upload("conversations/chat/alice/photo",PNG,"image/png"),false);
  assert.deepEqual(await storage.download("conversations/chat/alice/photo"),PNG);
  assert.equal(calls[0].init.headers["x-upsert"],"false");
  assert.match(calls[1].url,/\/object\/authenticated\/utechat-media\//);
  assert.equal(calls[1].init.redirect,"error");
  assert.equal(calls[1].init.headers.Authorization,undefined);
  assert.throws(()=>createStorage({url:"http://evil.example",key:"key"}));
});
test("direct conversations need no group member documents; notifications use root participants",async(t)=>{
  const f=await fixture(t);
  f.db.rows.delete("conversations/chat/members/alice");f.db.rows.delete("conversations/chat/members/bob");
  assert.equal((await f.request("/api/rtc-token","alice",{callId:"call1"})).status,200);
  assert.equal((await f.request("/api/message-notify","alice",{conversationId:"chat",messageId:"msg1"})).status,200);
  assert.equal(f.pushes[0].data.recipientUid,"bob");
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/photo","alice",PNG,"PUT","image/png")).status,200);
});
test("group images require both root participation and matching member document",async(t)=>{
  const f=await fixture(t,{rows:{"conversations/chat":{type:"group",participantIds:["alice","bob"],groupName:"Test"}}});
  f.db.rows.delete("conversations/chat/members/bob");
  assert.equal((await f.request("/api/media?path=conversations/chat/bob/photo","bob",PNG,"PUT","image/png")).status,403);
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/photo","alice",PNG,"PUT","image/png")).status,200);
  assert.equal((await f.request("/api/message-notify","alice",{conversationId:"chat",messageId:"msg1"})).status,200);
  assert.equal(f.pushes.length,0);
});
