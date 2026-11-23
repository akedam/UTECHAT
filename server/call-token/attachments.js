"use strict";

const MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
const attachmentTypes = new Map([
  ["pdf", "application/pdf"],
  ["docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
  ["txt", "text/plain"],
]);

function attachmentType(name) {
  if (typeof name !== "string" || name.length > 120 || name !== name.trim()
      || /[\\/\p{Cc}\p{Cf}]/u.test(name)) return undefined;
  const dot = name.lastIndexOf(".");
  return dot > 0 ? attachmentTypes.get(name.slice(dot + 1).toLowerCase()) : undefined;
}

function isWordDocument(bytes) {
  if (bytes.length < 22 || bytes.readUInt32LE(0) !== 0x04034b50) return false;
  const end = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (end < 0 || end + 22 > bytes.length || end + 22 + bytes.readUInt16LE(end + 20) !== bytes.length) return false;
  if (bytes.readUInt32LE(end + 4) !== 0) return false;
  let offset = bytes.readUInt32LE(end + 16);
  const entries = bytes.readUInt16LE(end + 10);
  if (offset + bytes.readUInt32LE(end + 12) !== end || entries > 4096) return false;
  const names = new Set();
  for (let index = 0; index < entries; index++) {
    if (offset + 46 > end || bytes.readUInt32LE(offset) !== 0x02014b50) return false;
    const nameEnd = offset + 46 + bytes.readUInt16LE(offset + 28);
    const next = nameEnd + bytes.readUInt16LE(offset + 30) + bytes.readUInt16LE(offset + 32);
    if (next > end || (bytes.readUInt16LE(offset + 8) & 1)) return false;
    const name = bytes.toString("utf8", offset + 46, nameEnd);
    if (/vbaProject\.bin$/i.test(name)) return false;
    names.add(name);
    offset = next;
  }
  return offset === end && names.has("[Content_Types].xml") && names.has("word/document.xml");
}

function validAttachment(bytes, type) {
  if (!Buffer.isBuffer(bytes) || !bytes.length || bytes.length > MAX_ATTACHMENT_BYTES) return false;
  if (type === attachmentTypes.get("pdf")) {
    const end = bytes.lastIndexOf("%%EOF");
    return /^%PDF-[12]\.\d/.test(bytes.toString("ascii", 0, 8)) && end >= Math.max(bytes.length - 1024, 8);
  }
  if (type === attachmentTypes.get("docx")) return isWordDocument(bytes);
  if (type !== attachmentTypes.get("txt")) return false;
  try {
    return !/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/.test(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch (_) { return false; }
}

module.exports = { MAX_ATTACHMENT_BYTES, attachmentType, validAttachment };
