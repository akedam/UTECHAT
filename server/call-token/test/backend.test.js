"use strict";
const test=require("node:test"), assert=require("node:assert/strict"), http=require("node:http");
const {createBackend}=require("../backend"), {createStorage}=require("../supabase-storage");
const {createTokenMinter}=require("../rtc-token");
const TIME=1_800_000_000_000;
const PNG=Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=","base64");
function fakeDb(initial) {
  const rows=new Map(Object.entries(initial).map(([key,value])=>[key,structuredClone(value)]));
  const doc=(path)=>({path,id:path.split("/").at(-1),get:async()=>snapshot(path),
    collection:(name)=>collection(`${path}/${name}`),
    update:async(data)=>rows.set(path,{...rows.get(path),...structuredClone(data)}),
    delete:async()=>rows.delete(path)});
  const snapshot=(path)=>({id:path.split("/").at(-1),ref:doc(path),exists:rows.has(path),data:()=>structuredClone(rows.get(path))});
  function collection(path,filters=[],max=Infinity) {
    return {path,doc:(id)=>doc(`${path}/${id}`),where:(key,operator,value)=>collection(path,[...filters,[key,operator,value]],max),
      limit:(count)=>collection(path,filters,count),get:async()=>({docs:[...rows.keys()]
        .filter((key)=>key.startsWith(path+"/") && key.split("/").length===path.split("/").length+1)
        .filter((key)=>filters.every(([field,op,value])=>op==='>='?rows.get(key)[field]>=value:op==='<'?rows.get(key)[field]<value:rows.get(key)[field]===value)).slice(0,max).map(snapshot)})};
  }
  let pending=Promise.resolve();
  return {rows,doc,collection,batch(){const refs=[];return{delete(ref){refs.push(ref);},async commit(){refs.forEach(ref=>rows.delete(ref.path));}};},
    async recursiveDelete(ref){for(const path of rows.keys())if(path.startsWith(ref.path+'/'))rows.delete(path);},runTransaction(fn){
    const task=pending.then(async()=>{
      const writes=[];
      const value=await fn({get:(ref)=>ref.get(),set:(ref,data)=>writes.push(()=>rows.set(ref.path,structuredClone(data))),
        update:(ref,data)=>writes.push(()=>rows.set(ref.path,{...rows.get(ref.path),...structuredClone(data)})),
        delete:(ref)=>writes.push(()=>rows.delete(ref.path))});
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
      async download(path){return objects.get(path);},async remove(paths){for(const path of paths)objects.delete(path);}},...options.backend,
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
test("an event with no registered recipient can retry after device registration", async (t) => {
  const f = await fixture(t);
  const device = "users/bob/deviceTokens/device";
  const event = { conversationId: "chat", messageId: "msg1" };
  f.db.rows.delete(device);
  const first = await f.request("/api/message-notify", "alice", event);
  assert.equal(first.status, 503);
  assert.equal(first.body.error, "recipient-device-unavailable");
  assert.equal(f.pushes.length, 0);
  f.db.rows.set(device, { token: "new-device-token" });
  assert.equal((await f.request("/api/message-notify", "alice", event)).status, 200);
  assert.equal((await f.request("/api/message-notify", "alice", event)).status, 200);
  assert.equal(f.pushes.length, 1);
});

test("voice objects are immutable and a recipient can read only after the voice message is published", async (t) => {
  const f = await fixture(t);
  const box = (name, payload) => {
    const size = Buffer.alloc(4);
    size.writeUInt32BE(payload.length + 8);
    return Buffer.concat([size, Buffer.from(name), payload]);
  };
  const audio = Buffer.concat([
    box("ftyp", Buffer.from("isom0000")), box("moov", Buffer.from("sample")), box("mdat", Buffer.from("audio")),
  ]);
  const path = "conversations/chat/alice/voice1";
  const route = "/api/voice?path=" + path;
  assert.equal((await f.request(route, "alice", PNG, "PUT", "audio/mp4")).status, 400);
  assert.equal((await f.request(route, "bob", audio, "PUT", "audio/mp4")).status, 403);
  assert.equal((await f.request(route, "alice", audio, "PUT", "audio/mp4")).status, 200);
  assert.equal((await f.request(route, "alice", audio, "PUT", "audio/mp4")).status, 200);
  const modified = Buffer.from(audio);
  modified[modified.length - 1] ^= 1;
  assert.equal((await f.request(route, "alice", modified, "PUT", "audio/mp4")).status, 409);
  assert.equal((await f.request(route, "bob", null, "GET")).status, 403);
  assert.equal((await f.request(route, "mallory", null, "GET")).status, 403);
  f.db.rows.set("conversations/chat/messages/voice1", { type: "voice", mediaPath: path, senderId: "alice" });
  assert.deepEqual((await f.request(route, "bob", null, "GET")).body, audio);
  f.db.rows.set("users/bob/blocked/alice", { createdAt: TIME });
  assert.equal((await f.request(route, "alice", audio, "PUT", "audio/mp4")).status, 403);
  assert.equal((await f.request("/api/rtc-token", "alice", { callId: "call1" })).status, 403);
  assert.equal((await f.request("/api/message-notify", "alice", { conversationId: "chat", messageId: "msg1" })).status, 200);
  assert.equal(f.pushes.length, 0);
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
  assert.deepEqual((await f.request("/healthz",null,{},"GET")).body,{status:"configured",apiVersion:1,media:false,voice:true});
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/x","alice",PNG,"PUT","image/png")).status,503);
});
test("Supabase adapter uses private authenticated access and immutable uploads",async()=>{
  const calls=[];
  const storage=createStorage({url:"https://testing.supabase.co",key:"sb_secret_testing",fetchImpl:async(url,init)=>{
    calls.push({url,init});return init.method==="POST"?new Response(JSON.stringify({error:"Duplicate"}),{status:400}):new Response(PNG);
  }});
  assert.equal(await storage.upload("conversations/chat/alice/photo",PNG,"image/png"),false);
  assert.deepEqual(await storage.download("conversations/chat/alice/photo"),PNG);
  await storage.remove(["conversations/chat/alice/photo"]);
  assert.equal(calls[0].init.headers["x-upsert"],"false");
  assert.match(calls[1].url,/\/object\/authenticated\/utechat-media\//);
  assert.equal(calls[1].init.redirect,"error");
  assert.equal(calls[1].init.headers.Authorization,undefined);
  assert.equal(calls[2].init.method,"DELETE");
  assert.equal(calls[2].url,"https://testing.supabase.co/storage/v1/object/utechat-media");
  assert.deepEqual(JSON.parse(calls[2].init.body),{prefixes:["conversations/chat/alice/photo"]});
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
test("real Agora SDK generates publisher tokens using its exported RtcRole",()=>{
  const appId="a".repeat(32),certificate="b".repeat(32);
  const mint=createTokenMinter(appId,certificate);
  const result=mint({channelName:"test-channel",rtcUidByUser:{alice:12345}},"alice");
  assert.equal(result.appId,appId);assert.equal(result.uid,12345);assert.equal(result.channelName,"test-channel");
  assert.match(result.token,/^007[A-Za-z0-9+/=]+$/);assert.ok(result.token.length>100);
  assert.throws(()=>createTokenMinter("missing","missing"));
});

test("only leader or sole actual member deletes a group; cleanup includes messages, members and private media",async(t)=>{
  const f=await fixture(t,{rows:{
    "conversations/group":{type:"group",participantIds:["alice","bob","mallory"]},
    "conversations/group/members/alice":{userId:"alice",role:"owner"},
    "conversations/group/members/bob":{userId:"bob",role:"admin"},
    "conversations/group/members/mallory":{userId:"mallory",role:"member"},
    "conversations/group/messages/old":{type:"text",content:"Old"},
    "backendMedia/image":{path:"conversations/group/alice/image",ready:true},
    "backendMedia/other":{path:"conversations/group-other/alice/image",ready:true},
  }});
  f.objects.set("conversations/group/alice/image",PNG);
  for(const uid of ['bob','mallory'])assert.equal((await f.request('/api/group-delete',uid,{conversationId:'group'})).status,403);
  assert.equal((await f.request('/api/group-delete','alice',{conversationId:'chat'})).status,403);
  assert.equal((await f.request('/api/group-delete','alice',{conversationId:'group'})).status,200);
  assert.equal([...f.db.rows.keys()].some(key=>key==='conversations/group'||key.startsWith('conversations/group/')),false);
  assert.equal(f.db.rows.has('backendMedia/image'),false);assert.equal(f.db.rows.has('backendMedia/other'),true);
  assert.equal(f.objects.size,0);
  assert.equal((await f.request('/api/group-delete','alice',{conversationId:'group'})).status,200);
  assert.equal((await f.request('/api/group-delete','bob',{conversationId:'group'})).status,403);
  f.db.rows.set('conversations/solo',{type:'group',participantIds:['bob']});
  f.db.rows.set('conversations/solo/members/bob',{userId:'bob',role:'member'});
  assert.equal((await f.request('/api/group-delete','alice',{conversationId:'solo'})).status,403);
  assert.equal((await f.request('/api/group-delete','bob',{conversationId:'solo'})).status,200);
});
test("interrupted deletion freezes writes and can resume with its original authorized actor",async(t)=>{
  const f=await fixture(t,{rows:{
    "conversations/group":{type:'group',participantIds:['alice','bob']},
    "conversations/group/members/alice":{userId:'alice',role:'owner'},
    "conversations/group/members/bob":{userId:'bob',role:'member'},
  }});
  const recursive=f.db.recursiveDelete;f.db.recursiveDelete=async()=>{throw Error('Unavailable');};
  assert.equal((await f.request('/api/group-delete','alice',{conversationId:'group'})).status,503);
  assert.equal(f.db.rows.get('conversations/group').lifecycle,'deleting');
  assert.equal((await f.request('/api/media?path=conversations/group/alice/new','alice',PNG,'PUT','image/png')).status,409);
  assert.equal((await f.request('/api/group-delete','bob',{conversationId:'group'})).status,403);
  f.db.rows.delete('conversations/group/members/alice'); // Partial cleanup must not strand the authorized retry.
  f.db.recursiveDelete=recursive;
  assert.equal((await f.request('/api/group-delete','alice',{conversationId:'group'})).status,200);
});
