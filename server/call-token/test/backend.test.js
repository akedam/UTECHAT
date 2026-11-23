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
  function collection(path, filters = [], max = Infinity, order = [], after = null) {
    const sortedValues = key => order.map(([field]) => field === "__name__" ? doc(key).id : rows.get(key)[field]);
    const compare = (left, right) => {
      for (let index = 0; index < order.length; index++) {
        if (left[index] === right[index]) continue;
        return (left[index] < right[index] ? -1 : 1) * (order[index][1] === "desc" ? -1 : 1);
      }
      return 0;
    };
    return {
      path,
      doc: id => doc(`${path}/${id}`),
      where: (key, operator, value) => collection(path, [...filters, [key, operator, value]], max, order, after),
      orderBy: (key, direction) => collection(path, filters, max, [...order, [key, direction]], after),
      startAfter: (...values) => collection(path, filters, max, order, values),
      limit: count => collection(path, filters, count, order, after),
      get: async () => ({ docs: [...rows.keys()]
        .filter(key => key.startsWith(path + "/") && key.split("/").length === path.split("/").length + 1)
        .filter(key => filters.every(([field, op, value]) => op === '>=' ? rows.get(key)[field] >= value
          : op === '<' ? rows.get(key)[field] < value : rows.get(key)[field] === value))
        .filter(key => !after || compare(sortedValues(key), after) > 0)
        .sort((a, b) => compare(sortedValues(a), sortedValues(b))).slice(0, max).map(snapshot) })
    };
  }
  let pending=Promise.resolve();
  return {rows,doc,collection,batch(){const writes=[];return{
    delete(ref){writes.push(()=>rows.delete(ref.path));},
    set(ref,data){writes.push(()=>rows.set(ref.path,structuredClone(data)));},
    async commit(){writes.forEach(write=>write());}
  };},
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
    auth:{
      async verifyIdToken(token){
        if(token==="legacy-alice" || token==="legacy-otp-alice")return{uid:"alice",email:"alice@example.test",email_verified:true,
          firebase:{sign_in_provider:token==="legacy-alice" ? "password" : "phone"}};
        if(!["alice","bob","mallory"].includes(token))throw Error();
        return{uid:token,phone_number:"+16505550101",utechatPhone:true,firebase:{sign_in_provider:"custom"}};
      },
      async getUser(uid){return (options.unverifiedRecipients || []).includes(uid)
        ? { email: "legacy@example.test", emailVerified: true }
        : { phoneNumber: "+16505550101", customClaims: { utechatPhone: true } };},
      async getUserByPhoneNumber(phone) {
        const account = options.phoneAccounts?.[phone];
        if (!account) throw Object.assign(new Error("unknown phone"), { code: "auth/user-not-found" });
        return account;
      },
    },
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
  return{db,backend,pushes,objects,request,base:`http://127.0.0.1:${server.address().port}`,get tokenCalls(){return tokenCalls;}};
}

test("browser preflight permits UTEChat origins and leaves API authentication intact", async t => {
  const {base}=await fixture(t);
  const trusted="https://utechat-ab9d8.web.app";
  const preflight=(origin,method,route="/api/rtc-token")=>fetch(base+route,{
    method:"OPTIONS",headers:{Origin:origin,"Access-Control-Request-Method":method,
      "Access-Control-Request-Headers":"authorization, content-type"},
  });
  const expected=await preflight(trusted,"POST");
  assert.equal(expected.status,204);
  assert.equal(expected.headers.get("access-control-allow-origin"),trusted);
  assert.equal(expected.headers.get("vary"),"Origin");
  const foreign=await preflight("https://utechat-ab9d8.web.app.example.org","POST");
  assert.equal(foreign.status,403);
  assert.equal(foreign.headers.get("access-control-allow-origin"),null);
  assert.equal((await preflight(trusted,"DELETE","/api/voice")).status,404);
  const anonymous=await fetch(base+"/api/rtc-token",{method:"POST",headers:{Origin:trusted}});
  assert.equal(anonymous.status,401);
  assert.equal(anonymous.headers.get("access-control-allow-origin"),trusted);
});

test("phone lookup requires a verified session, returns only public fields and limits repeated searches", async t => {
  const { request } = await fixture(t, {
    rows: { "publicProfiles/bob": { displayName: "Bob", photoUrl: "avatar", phone: "private" } },
    phoneAccounts: {
      "+84900000001": { uid: "bob", customClaims: { utechatPhone: true } },
      "+84900000002": { uid: "alice", customClaims: { utechatPhone: true } },
      "+84900000003": { uid: "bob" },
      "+84900000004": { uid: "bob", disabled: true, customClaims: { utechatPhone: true } },
    },
  });
  const route = "/api/friends/lookup";
  assert.equal((await request(route, null, { phone: "+84900000001" })).status, 401);
  assert.equal((await request(route, "legacy-alice", { phone: "+84900000001" })).status, 403);
  assert.equal((await request(route, "alice", { phone: "+8490" })).status, 400);
  assert.deepEqual(await request(route, "alice", { phone: "+84900000001" }),
    { status: 200, body: { user: { id: "bob", displayName: "Bob", photoUrl: "avatar" } } });
  for (const phone of ["+84900000002", "+84900000003", "+84900000004", "+84900000005", "+84900000006"]) {
    assert.deepEqual(await request(route, "alice", { phone }), { status: 200, body: { user: null } });
  }
  assert.equal((await request(route, "alice", { phone: "+84900000001" })).status, 429);
});

test("phone lookup conceals targets blocked in either direction", async t => {
  const { request, db } = await fixture(t, {
    rows: { "publicProfiles/bob": { displayName: "Bob" } },
    phoneAccounts: { "+84900000001": { uid: "bob", customClaims: { utechatPhone: true } } },
  });
  for (const path of ["users/alice/blocked/bob", "users/bob/blocked/alice"]) {
    db.rows.set(path, {});
    assert.deepEqual(await request("/api/friends/lookup", "alice", { phone: "+84900000001" }),
      { status: 200, body: { user: null } });
    db.rows.delete(path);
  }
});

test("phone authentication is disabled unless the server explicitly enables it", async t => {
  const { request } = await fixture(t);
  assert.deepEqual(await request("/api/phone-auth/status", null, null, "GET"),
    { status: 200, body: { enabled: false } });
  assert.equal((await request("/api/phone-auth/login", null, {})).status, 503);
});

test("OTP proofs can set or reset phone passwords without unlocking chat", async t => {
  const phone = "+16505550101", uid = "phone-user";
  const legacyPhone = "+16505550102";
  const account = { uid, phoneNumber: phone };
  const legacy = { uid: "alice", email: "alice@example.test", emailVerified: true, phoneNumber: legacyPhone, customClaims: { utechatPhone: true } };
  const accounts = [account, legacy];
  const start = Math.floor(TIME / 1000);
  let millis = TIME, revocations = 0;
  const claims = (user, provider, date) => ({ uid: user.uid, phone_number: user.phoneNumber, email: user.email,
    firebase: { sign_in_provider: provider }, email_verified: user.emailVerified, auth_time: date, ...user.customClaims });
  const { db, request } = await fixture(t, { backend: { phoneAuthEnabled: true, now: () => millis, auth: {
    async verifyIdToken(token) {
      if (token === "signup-otp") return claims(account, "phone", start);
      if (token === "reset-otp") return claims(account, "phone", start + 1);
      if (token === "next-otp") return claims(account, "phone", start + 2);
      if (token === "ready") return claims(account, "custom", start);
      if (token === "linked-email") return claims(legacy, "password", start);
      if (token === "linked-otp") return claims(legacy, "phone", start + 2);
      if (token === "linked-custom") return claims(legacy, "custom", start);
      throw new Error("unknown test token");
    },
    async getUser(uid) { return accounts.find(user => user.uid === uid); },
    async getUserByPhoneNumber(number) {
      const user = accounts.find(candidate => candidate.phoneNumber === number);
      if (!user) throw Object.assign(new Error("not found"), { code: "auth/user-not-found" });
      return user;
    },
    async setCustomUserClaims(uid, customClaims) { Object.assign(await this.getUser(uid), { customClaims }); },
    async updateUser(uid, data) { Object.assign(await this.getUser(uid), data); },
    async revokeRefreshTokens(uid) { assert.equal(uid, account.uid); revocations++; },
    async createCustomToken(uid, extra) { assert.deepEqual(extra, { utechatPhone: true }); return "session-" + uid; }
  } } });
  const action = (name, token, fields) => request("/api/phone-auth/" + name, token, fields);
  const login = (password, number = phone) => action("login", null, { phone: number, password });
  const first = "first-password-33", next = "next-password-66";
  assert.equal((await action("register", null, { password: first })).status, 401);
  assert.equal((await action("register", "signup-otp", { displayName: "Phone User", password: first })).status, 200);
  assert.equal(db.rows.get("users/" + uid).email, "");
  assert.equal(db.rows.get("publicProfiles/" + uid).displayName, "Phone User");
  assert.equal((await request("/api/rtc-token", "signup-otp", { callId: "call1" })).status, 403);
  assert.equal((await request("/api/rtc-token", "ready", { callId: "call1" })).body.error, "forbidden");
  assert.deepEqual(await login(first), { status: 200, body: { token: "session-" + uid } });
  assert.equal((await login(next)).status, 401);
  assert.equal((await action("register", "signup-otp", { displayName: "Changed", password: next })).status, 409);
  assert.equal((await action("reset", "ready", { password: next })).status, 403);
  millis += 2_000;
  assert.equal((await action("reset", "reset-otp", { password: next })).status, 200);
  assert.equal(revocations, 1);
  assert.equal((await action("reset", "reset-otp", { password: "different-123" })).status, 409);
  assert.equal((await action("reset", "next-otp", { password: first })).body.error, "phone-password-reused");
  assert.equal((await login(first)).status, 401);
  assert.equal((await login(next)).status, 200);
  db.rows.set("backendPhoneCredentials/alice", { ...db.rows.get("backendPhoneCredentials/" + uid), phone: legacyPhone });
  assert.equal((await action("link", "linked-otp", { password: first, emailProof: "linked-email" })).status, 404);
  assert.equal((await action("register", "linked-otp", { password: next, displayName: "Legacy" })).status, 409);
  assert.equal((await action("reset", "linked-otp", { password: next })).status, 409);
  assert.equal((await request("/api/rtc-token", "linked-custom", { callId: "call1" })).status, 403);
  assert.equal(db.rows.get("publicProfiles/alice").displayName, "Alice");
  assert.equal((await login(next, legacyPhone)).status, 401);
  for (let attempt = 0; attempt < 6; attempt++) assert.equal((await login(first)).status, 401);
  assert.equal((await login(next)).status, 429);
  millis += 15 * 60_000;
  assert.equal((await login(next)).status, 200);
});

test("attachments publish once and downloads require current conversation access", async t => {
  const f = await fixture(t);
  const path = "conversations/chat/alice/attachment1";
  const route = `/api/attachment?path=${encodeURIComponent(path)}`;
  const upload = (uid, bytes, name = "Bài tập.pdf", type = "application/pdf") =>
    f.request(`${route}&name=${encodeURIComponent(name)}`, uid, bytes, "PUT", type);
  const pdf = Buffer.from("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n");
  assert.equal((await upload("bob", pdf)).status, 403);
  assert.equal((await upload("alice", PNG)).status, 400);
  assert.equal((await upload("alice", pdf, "../file.pdf")).status, 400);
  assert.equal((await upload("alice", pdf, "file.exe")).status, 400);
  assert.equal((await f.request(route, "bob", null, "GET")).status, 404);
  const results = await Promise.all([upload("alice", pdf), upload("alice", pdf)]);
  for (const result of results) assert.deepEqual(result, { status: 200,
    body: { path, size: pdf.length, contentType: "application/pdf" } });
  assert.equal(f.objects.size, 1);
  const message = f.db.rows.get("conversations/chat/messages/attachment1");
  assert.equal(message.fileName, "Bài tập.pdf");
  assert.equal(message.type, "file");
  assert.equal(message.fileSize, pdf.length);
  assert.equal(f.db.rows.get("conversations/chat").lastMessageType, "file");
  assert.equal((await upload("alice", pdf, "renamed.pdf")).status, 409);
  assert.deepEqual((await f.request(route, "bob", null, "GET")).body, pdf);
  assert.equal((await f.request(route, "mallory", null, "GET")).status, 403);
  assert.equal((await f.request(`/api/media?path=${path}`, "bob", null, "GET")).status, 404);
  f.db.rows.set("conversations/chat/messages/attachment1", { ...message, type: "recalled" });
  assert.equal((await f.request(route, "alice", null, "GET")).status, 404);
  assert.equal((await upload("alice", pdf)).status, 409);
});

test("attachments reject blocked senders and removed group members", async t => {
  const f = await fixture(t, { rows: {
    "conversations/group": { type: "group", participantIds: ["alice", "bob"] },
    "conversations/group/members/alice": { userId: "alice" },
    "conversations/group/members/bob": { userId: "bob" },
    "users/bob/blocked/alice": {},
  } });
  const direct = "/api/attachment?path=conversations/chat/alice/attachment1&name=notes.txt";
  const group = "/api/attachment?path=conversations/group/alice/attachment1";
  const notes = Buffer.from("Ghi chú nhóm", "utf8");
  assert.equal((await f.request(direct, "alice", notes, "PUT", "text/plain")).status, 403);
  assert.equal((await f.request(`${group}&name=notes.txt`, "alice", notes, "PUT", "text/plain")).status, 200);
  f.db.rows.delete("conversations/group/members/bob");
  assert.equal((await f.request(group, "bob", null, "GET")).status, 403);
});

test("Word attachments preserve the original OOXML file and reject an unrelated ZIP", async t => {
  const f = await fixture(t);
  const route = "/api/attachment?path=conversations/chat/alice/word1";
  const type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  // Minimal OOXML document containing the word UTEChat.
  const word = Buffer.from(
    "UEsDBBQAAAAIAAAAISh0JJxTxQAAAD4BAAATAAAAW0NvbnRlbnRfVHlwZXNdLnhtbJSQMU7EQAxFrxJNi3a8oqBASRqgBQou" +
    "YE2cZMTYHo29S7g9ygJb0FH7/f++3L99VrJu4yI2hNW93gNYWonRolaSjcusjdEtalugYnrHheD2eLyDpOIkfvC9I4z9I814" +
    "Kt49bU5iWWUIjYqF7uEb3F1DwFpLTuhZBc4y/bEcfgyxUbkwtuZqNxuXAGP/cqbW8kTdKzZ/RqYhwIe2CSZNJybxuIP/8uk8" +
    "50TX/N5WmyYyy7JwidcLY5bfHXB52/gFAAD//wMAUEsDBBQAAAAIAAAAIShhey9DkAAAAPIAAAALAAAAX3JlbHMvLnJlbHOM" +
    "zzEOAiEQBdCrkDnAzmphYYDKZluzFyAwuxCBIYBxvb2NxWosbH9+3s+XV4qmB87Nh9LElmJuCnzv5YzYrKdk2sCF8pbiwjWZ" +
    "3gauKxZjb2YlPI7jCeveAC33ppicgjq5A4j5Wegfm5clWLqwvSfK/cfEVwPEbOpKXcGDq0P3joctRUAt8eOifgEAAP//AwBQ" +
    "SwMEFAAAAAgAAAAhKFyOFPt/AAAAmQAAABEAAAB3b3JkL2RvY3VtZW50LnhtbETOMQ7CMAwF0KugHqCuGBiikAVxAzhASENT" +
    "KY4j21XK7VHKwPK+/vClb5uZKWwYi552zEVMuw5JtRoACSmil5FqLDvmNzF6lZF4gUY8V6YQRdayYIbzNF0A/VoGZ5t50fzp" +
    "WTvcUfd83G/Jq4VeunxYD38D+J9xXwAAAP//AwBQSwECFAAUAAAACAAAACEodCScU8UAAAA+AQAAEwAAAAAAAAAAAAAAAAAA" +
    "AAAAW0NvbnRlbnRfVHlwZXNdLnhtbFBLAQIUABQAAAAIAAAAIShhey9DkAAAAPIAAAALAAAAAAAAAAAAAAAAAPYAAABfcmVs" +
    "cy8ucmVsc1BLAQIUABQAAAAIAAAAIShcjhT7fwAAAJkAAAARAAAAAAAAAAAAAAAAAK8BAAB3b3JkL2RvY3VtZW50LnhtbFBL" +
    "BQYAAAAAAwADALkAAABdAgAAAAA=", "base64");
  const zip = Buffer.from(word);
  const entry = Buffer.from("word/document.xml");
  for (let offset = zip.indexOf(entry); offset >= 0; offset = zip.indexOf(entry, offset + entry.length)) {
    Buffer.from("word/different.xml".slice(0, entry.length)).copy(zip, offset);
  }
  assert.equal((await f.request(route + "&name=test.docx", "alice", zip, "PUT", type)).status, 400);
  assert.equal((await f.request(route + "&name=test.docx", "alice", word, "PUT", type)).status, 200);
  assert.deepEqual((await f.request(route, "bob", null, "GET")).body, word);
});
test("Moments are private, deduplicated, and removable only by their author", async t => {
  const f = await fixture(t, { rows: {
    "friendships/alice_bob": { fromUid: "alice", toUid: "bob", status: "accepted" }
  } });
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xc0, 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0, 0xff, 0xd9]);
  const upload = { nonce: "first-capture", caption: "Xin chào", recipients: ["bob"], image: jpeg.toString("base64") };
  const put = (body = upload, uid = "alice") => f.request("/api/moments", uid, body, "PUT");
  const get = (path, uid) => f.request(path, uid, null, "GET");
  assert.equal((await put(upload, "legacy-alice")).status, 403);
  const oversized = Buffer.from(jpeg);
  oversized.writeUInt16BE(961, 7);
  oversized.writeUInt16BE(961, 9);
  assert.equal((await put({ ...upload, image: oversized.toString("base64") })).status, 400);
  assert.equal((await put({ ...upload, recipients: ["bob", "bob"] })).status, 400);
  assert.equal((await put({ ...upload, recipients: ["mallory"] })).status, 403);
  const sent = await put();
  assert.equal(sent.status, 200);
  const { id } = sent.body;
  assert.equal((await put()).body.id, id);
  assert.equal((await put({ ...upload, caption: "Changed" })).status, 409);
  assert.equal(f.objects.size, 1);
  assert.equal(f.pushes.length, 1);
  assert.deepEqual(f.pushes[0].data, { eventType: "moment", momentId: id, recipientUid: "bob" });
  const detail = "/api/moment?id=" + id;
  const photo = "/api/moment-photo?id=" + id;
  assert.equal((await get(detail, "mallory")).status, 404);
  assert.equal((await get(photo, "mallory")).status, 404);
  assert.equal((await get("/api/moments", "mallory")).body.items.length, 0);
  assert.equal((await get("/api/moments", "bob")).body.items[0].id, id);
  assert.deepEqual((await get(photo, "bob")).body, jpeg);
  assert.equal((await get(detail, "bob")).body.recipients, undefined);
  const reply = (action, value) => f.request("/api/moment", "bob", { id, action, value });
  assert.equal((await reply("seen")).body.seen, true);
  assert.equal((await reply("react", "🔥")).status, 400);
  assert.equal((await reply("react", "❤️")).body.reaction, "❤️");
  assert.equal((await reply("report", "spam")).status, 200);
  assert.equal((await reply("report", "abuse")).status, 200);
  assert.equal([...f.db.rows.keys()].filter(key => key.startsWith("backendMomentReports/")).length, 1);
  assert.equal((await reply("hide")).status, 200);
  assert.equal((await get("/api/moments", "bob")).body.items.length, 0);
  assert.deepEqual((await get(photo, "bob")).body, jpeg);
  assert.deepEqual((await get(detail, "alice")).body.recipients, [{ id: "bob", seen: true, reaction: "❤️" }]);
  assert.equal((await f.request(detail, "bob", null, "DELETE")).status, 404);
  f.db.rows.set("users/bob/blocked/alice", {});
  assert.equal((await get(photo, "bob")).status, 404);
  assert.equal((await get("/api/moments", "bob")).body.items.length, 0);
  assert.equal((await put({ ...upload, nonce: "blocked" })).status, 403);
  f.db.rows.delete("users/bob/blocked/alice");
  assert.equal((await f.request(detail, "alice", null, "DELETE")).status, 200);
  assert.equal((await f.request(detail, "alice", null, "DELETE")).status, 200);
  assert.equal((await get(photo, "bob")).status, 404);
  assert.equal((await get("/api/moments?view=mine", "alice")).body.items.length, 0);
  assert.equal(f.objects.size, 0);
});

test("combined Tin feed paginates self and friend posts in a single newest-first order", async t => {
  const f = await fixture(t, { rows: {
    "friendships/alice_bob": { fromUid: "alice", toUid: "bob", status: "accepted" }
  } });
  const ids = Array.from({ length: 19 }, (_, index) => (index + 1).toString(16).padStart(64, "0"));
  for (let index = 0; index < ids.length; index++) {
    const own = index % 2 === 0;
    const id = ids[index], createdAt = TIME + Math.floor(index / 2);
    f.db.rows.set(`users/alice/${own ? "sentMoments" : "receivedMoments"}/${id}`, { createdAt });
    f.db.rows.set(`backendMoments/${id}`, {
      owner: own ? "alice" : "bob", recipients: [own ? "bob" : "alice"], state: "ready", createdAt
    });
  }
  const get = path => f.request(path, "alice", null, "GET");
  const first = (await get("/api/moments?view=all")).body;
  assert.equal(first.items.length, 16);
  assert(first.items.some(item => item.own) && first.items.some(item => !item.own));
  const last = (await get("/api/moments?view=all&before=" + encodeURIComponent(first.next))).body;
  assert.deepEqual([...first.items, ...last.items].map(item => item.id), [...ids].reverse());
  assert.equal(last.next, null);
  assert((await get("/api/moments?view=mine")).body.items.every(item => item.own));
});

test("a Tin reply stores only text in the author's direct conversation", async t => {
  const id = "a".repeat(64);
  const f = await fixture(t, { rows: {
    "friendships/alice_bob": { fromUid: "alice", toUid: "bob", status: "accepted" },
    ["backendMoments/" + id]: { owner: "alice", state: "ready", recipients: ["bob"] },
    "conversations/group1": { type: "group", participantIds: ["alice", "bob", "mallory"] },
    "conversations/other": { type: "direct", participantIds: ["bob", "mallory"] }
  } });
  const reply = (uid, conversationId, options) => f.request("/api/moment", uid, {
    action: "reply", id, conversationId, nonce: "test-reply", value: "Ảnh đẹp!", ...options
  });
  assert.equal((await reply("mallory", "other")).status, 404);
  assert.equal((await reply("alice", "chat")).status, 404);
  assert.equal((await reply("bob", "chat", { value: "  " })).status, 400);
  assert.equal((await reply("bob", "other")).status, 403);
  assert.equal((await reply("bob", "group1")).status, 403);
  const posted = await reply("bob", "chat");
  assert.equal(posted.status, 200);
  const path = "conversations/chat/messages/" + posted.body.messageId;
  const saved = f.db.rows.get(path);
  assert.equal(saved.senderId, "bob");
  assert.equal(saved.momentId, undefined);
  assert.equal(saved.replyTo, undefined);
  assert.equal(saved.content, "Ảnh đẹp!");
  assert.equal(saved.mediaPath, undefined);
  assert.equal(f.db.rows.get("conversations/chat").lastMessageId, posted.body.messageId);
  assert.deepEqual(await reply("bob", "chat"), posted);
  assert.equal((await reply("bob", "chat", { value: "Khác" })).status, 409);
  assert.equal([...f.db.rows.keys()].filter(key => key.startsWith("conversations/chat/messages/tin_")).length, 1);
  f.db.rows.set("users/alice/blocked/bob", {});
  assert.equal((await reply("bob", "chat", { nonce: "another" })).status, 404);
});
test("verified email alone gives no chat API access and receives no notifications", async t => {
  const f = await fixture(t, { unverifiedRecipients: ["bob"] });
  const blocked = await f.request("/api/rtc-token", "legacy-alice", {callId:"call1"});
  assert.deepEqual(blocked, {status:403,body:{error:"phone-session-required"}});
  assert.equal(f.tokenCalls, 0);
  assert.equal((await f.request("/api/voice?path=conversations/chat/alice/v1", "legacy-alice", null, "GET")).status, 403);
  assert.equal((await f.request("/api/message-notify", "alice", {conversationId:"chat",messageId:"msg1"})).status, 200);
  assert.equal(f.pushes.length, 0);
});
test("cutover verification can preserve old email sessions without granting access to an OTP proof", async t => {
  const f = await fixture(t, { backend: { phoneAuthEnabled: true, legacyEmailAccess: true }, unverifiedRecipients: ["bob"] });
  assert.equal((await f.request("/api/rtc-token", "legacy-alice", {callId:"call1"})).status, 200);
  assert.equal((await f.request("/api/rtc-token", "legacy-otp-alice", {callId:"call1"})).status, 403);
  assert.equal((await f.request("/api/message-notify", "alice", {conversationId:"chat",messageId:"msg1"})).status, 200);
  assert.equal(f.pushes.length, 1);
});
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
test("avatar uploads require a verified owner, enforce size and keep a single public image", async t => {
  const fixtureData = await fixture(t);
  const jpeg = Buffer.from([255, 216, 255, 224, 0, 6, 74, 70, 73, 70, 255, 217]);
  const put = (uid, bytes = jpeg) => fixtureData.request("/api/avatar?uid=bob", uid, bytes, "PUT", "image/jpeg");
  assert.equal((await put(null)).status, 401);
  assert.equal((await put("legacy-alice")).status, 403);
  assert.equal((await put("alice", PNG)).status, 400);
  assert.equal((await put("alice", Buffer.alloc(192 * 1024 + 1))).status, 413);
  const uploaded = await put("alice");
  assert.equal(uploaded.status, 200);
  assert.match(uploaded.body.path, /^\/api\/avatar\?uid=alice&v=[a-f0-9]{64}$/);
  assert.deepEqual((await fixtureData.request(uploaded.body.path, null, null, "GET")).body, jpeg);
  assert.equal((await fixtureData.request("/api/avatar?uid=bob", null, null, "GET")).status, 404);
  assert.equal((await put("alice")).body.path, uploaded.body.path);
  const replacement = Buffer.from(jpeg);
  replacement[9]++;
  assert.notEqual((await put("alice", replacement)).body.path, uploaded.body.path);
  assert.deepEqual((await fixtureData.request(uploaded.body.path, null, null, "GET")).body, replacement);
});
test("membership removal prevents token issuance even for a listed call participant",async(t)=>{
  const f=await fixture(t);await f.db.doc("conversations/chat").update({participantIds:["alice","mallory"]});
  assert.equal((await f.request("/api/rtc-token","bob",{callId:"call1"})).status,403);
});
test("recall suppresses queued notifications and denies attachment fetches", async t => {
  const f = await fixture(t);
  const path = "conversations/chat/alice/msg1";
  const mediaRoute = "/api/media?path=" + path;
  assert.equal((await f.request(mediaRoute, "alice", PNG, "PUT", "image/png")).status, 200);
  await f.db.doc("conversations/chat/messages/msg1").update({type:"recalled",content:"Tin nhắn đã được thu hồi"});
  assert.equal((await f.request(mediaRoute, "alice", null, "GET")).status, 404);
  assert.equal((await f.request("/api/message-notify", "alice", {conversationId:"chat",messageId:"msg1"})).status, 200);
  assert.equal(f.pushes.length, 0);
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

test("web message previews are opt-in per device and muted chats still ring for calls", async (t) => {
  const f = await fixture(t, { rows: {
    "users/bob/deviceTokens/device": { token: "android", platform: "android" },
    "users/bob/deviceTokens/private": { token: "private", platform: "web" },
    "users/bob/deviceTokens/preview": { token: "preview", platform: "web", showPreview: true },
    "users/bob/deviceTokens/muted": { token: "muted", platform: "web", showPreview: true, mutedChats: ["chat"] }
  } });
  assert.equal((await f.request("/api/message-notify", "alice", { conversationId: "chat", messageId: "msg1" })).status, 200);
  const messages = Object.fromEntries(f.pushes.map(push => [push.tokens[0], push.data]));
  assert.equal(messages.android.preview, "Hello");
  assert.equal(messages.preview.showPreview, "true");
  assert.equal(messages.preview.preview, "Hello");
  assert.equal(messages.private.preview, undefined);
  assert.equal(messages.private.senderName, undefined);
  assert.equal(messages.muted, undefined);
  await f.db.doc("calls/call1").update({ status: "ringing" });
  assert.equal((await f.request("/api/call-invite", "alice", { callId: "call1" })).status, 200);
  assert.ok(f.pushes.at(-1).tokens.includes("muted"));
  assert.equal(f.pushes.at(-1).data.eventType, "call");
});

test("a conversation muted on all web devices consumes the event without delivering push", async (t) => {
  const f = await fixture(t, { rows: {
    "users/bob/deviceTokens/device": { token: "muted", platform: "web", mutedChats: ["chat"] }
  } });
  const event = { conversationId: "chat", messageId: "msg1" };
  assert.equal((await f.request("/api/message-notify", "alice", event)).status, 200);
  await f.db.doc("users/bob/deviceTokens/device").update({ mutedChats: [] });
  assert.equal((await f.request("/api/message-notify", "alice", event)).status, 200);
  assert.equal(f.pushes.length, 0);
});

test("voice objects are immutable and a recipient can read only after the voice message is published", async (t) => {
  const f = await fixture(t);
  const box = (name, payload) => {
    const size = Buffer.alloc(4);
    size.writeUInt32BE(payload.length + 8);
    return Buffer.concat([size, Buffer.from(name), payload]);
  };
  const mdat = Buffer.alloc(16);
  mdat.writeUInt32BE(1);
  mdat.write("mdat", 4);
  mdat.writeBigUInt64BE(21n, 8);
  const metadata = Buffer.concat([box("ftyp", Buffer.from("isom0000")), box("moov", Buffer.from("sample"))]);
  const audio = Buffer.concat([metadata, mdat, Buffer.from("audio")]);
  const path = "conversations/chat/alice/voice1";
  const route = "/api/voice?path=" + path;
  assert.equal((await f.request(route, "alice", PNG, "PUT", "audio/mp4")).status, 400);
  assert.equal((await f.request(route, "alice", audio.subarray(0, -1), "PUT", "audio/mp4")).status, 400);
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
  assert.deepEqual((await f.request("/healthz",null,{},"GET")).body,{status:"configured",apiVersion:1,media:false,voice:true,moments:false});
  assert.equal((await f.request("/api/media?path=conversations/chat/alice/x","alice",PNG,"PUT","image/png")).status,503);
});
test("Tin retries a storage outage without spending quota twice or exposing an incomplete upload", async t => {
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xc0, 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0, 0xff, 0xd9]);
  const files = new Map();
  const f = await fixture(t, {
    rows: { "friendships/alice_bob": { fromUid: "alice", toUid: "bob", status: "accepted" } },
    backend: { dailyUploadBytes: jpeg.length, storage: {
      async upload(path, bytes) {
        if (files.has(path)) return false;
        files.set(path, Buffer.from(bytes));
        throw new Error("Storage response lost");
      },
      async download(path) { return files.get(path); }
    } }
  });
  const upload = { nonce: "retry", caption: "Ảnh thử", recipients: ["bob"], image: jpeg.toString("base64") };
  const put = () => f.request("/api/moments", "alice", upload, "PUT");
  assert.deepEqual(await put(), { status: 503, body: { error: "moment-storage-unavailable" } });
  assert.deepEqual((await f.request("/api/moments", "bob", null, "GET")).body.items, []);
  const result = await put();
  assert.equal(result.status, 200);
  assert.equal((await put()).body.id, result.body.id);
  assert.equal(files.size, 1);
  assert.equal((await f.request("/api/moments", "bob", null, "GET")).body.items[0].id, result.body.id);
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
