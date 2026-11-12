"use strict";
const { RtcTokenBuilder, RtcRole } = require("agora-token");
function createTokenMinter(appId, appCertificate) {
  if (!/^[a-fA-F0-9]{32}$/.test(appId || "") || !/^[a-fA-F0-9]{32}$/.test(appCertificate || ""))
    throw new Error("Real Agora credentials are required.");
  return (call, uid) => {
    const rtcUid = call.rtcUidByUser[uid], seconds = 30 * 60;
    return { appId, token: RtcTokenBuilder.buildTokenWithUid(appId, appCertificate, call.channelName,
      rtcUid, RtcRole.PUBLISHER, seconds, seconds), channelName: call.channelName, uid: rtcUid };
  };
}
module.exports = { createTokenMinter };
