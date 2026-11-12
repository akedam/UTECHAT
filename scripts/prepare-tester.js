"use strict";
// Generates one persistent signing identity. Secrets are written only to ignored local files.
const fs=require("node:fs"),path=require("node:path"),crypto=require("node:crypto"),cp=require("node:child_process");
const root=path.resolve(__dirname,"..");
const endpoint=process.argv[2];
let url;
try {url=new URL(endpoint);} catch(_) {throw new Error("Provide the real HTTPS backend origin.");}
if(url.protocol!=="https:" || url.username || url.password || url.pathname!=="/" || url.search || url.hash)
  throw new Error("Expected HTTPS backend origin without a path or credentials.");
async function main(){
  const response=await fetch(`${url.origin}/healthz`,{redirect:"error",signal:AbortSignal.timeout(110_000)});
  const health=await response.json();
  if(!response.ok || health.status!=="configured" || health.apiVersion!==1 || !health.media)
    throw new Error("Backend/private image storage is not ready; do not distribute an APK yet.");
  const settings=path.join(root,"agora.local.properties");
  const previous=fs.readFileSync(settings,"utf8");
  const appId=previous.match(/^utechatAgoraAppId=([a-fA-F0-9]{32})\s*$/m)?.[1];
  if(!appId)throw new Error("Run the local Agora configuration first.");
  fs.writeFileSync(settings,`utechatAgoraAppId=${appId}\nutechatAgoraTokenServerUrl=${url.origin}/api/rtc-token\nutechatUseBackendMedia=true\n`);
  const config=path.join(root,"tester-signing.local.properties"),privateDir=path.join(root,".private");
  fs.mkdirSync(privateDir,{recursive:true});
  if(!fs.existsSync(config)){
    const keystore=path.join(privateDir,"utechat-tester.jks");
    if(fs.existsSync(keystore))throw new Error("Existing keystore found without its settings. Restore its passwords; do not replace it.");
    const jdks=path.join(root,".gradle-user-home","jdks");
    const findKeytool=(dir,depth=0)=>{
      if(depth>4 || !fs.existsSync(dir))return null;
      for(const entry of fs.readdirSync(dir,{withFileTypes:true})){
        const full=path.join(dir,entry.name);
        if(entry.isFile() && entry.name==="keytool.exe")return full;
        if(entry.isDirectory()){const found=findKeytool(full,depth+1);if(found)return found;}
      }return null;
    };
    const keytool=findKeytool(jdks) || (process.env.JAVA_HOME && path.join(process.env.JAVA_HOME,"bin","keytool.exe"));
    if(!keytool || !fs.existsSync(keytool))throw new Error("JDK keytool not found.");
    const password=crypto.randomBytes(32).toString("hex");
    const result=cp.spawnSync(keytool,["-genkeypair","-keystore",keystore,"-storetype","JKS","-alias","utechat-tester",
      "-keyalg","RSA","-keysize","3072","-validity","10000","-dname","CN=UTEChat Tester",
      "-storepass:env","UTECHAT_SIGNING_PASSWORD","-keypass:env","UTECHAT_SIGNING_PASSWORD","-noprompt"],
      {env:{...process.env,UTECHAT_SIGNING_PASSWORD:password},stdio:["ignore","pipe","pipe"],windowsHide:true});
    if(result.status!==0)throw new Error("Could not generate signing keystore.");
    fs.writeFileSync(config,`storeFile=.private/utechat-tester.jks\nstorePassword=${password}\nkeyAlias=utechat-tester\nkeyPassword=${password}\n`,{mode:0o600});
  }
  console.log("Tester endpoint and signing are ready. Keep .private and tester-signing.local.properties backed up privately.");
}
main().catch(()=>{console.error("Tester preparation failed. Verify HTTPS backend health/media, Agora local config and the JDK; no keys were printed.");process.exitCode=1;});
