import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import Database from "better-sqlite3";
import { WAMessageStatus } from "@whiskeysockets/baileys";
import { classifyNotificationPolicy, isWithinWorkHours, localIso, loadConfig, type Config } from "../src/config.js";
import { isEligibleMessage, shouldProcessUpsert, type IncomingMessage } from "../src/message-filter.js";
import { Notifier } from "../src/notifier.js";
import { extractMessagePreview } from "../src/message-preview.js";
import { ConnectionOutageGuard } from "../src/connection-events.js";
import { clearReadNotificationQueues, persistMessageIdentity, recordNotifyMessages } from "../src/notify-handler.js";
import { WatcherState } from "../src/watcher-state.js";

const config: Config = { timezone: "America/Sao_Paulo", checkCron: "* * * * *", workDays: [1,2,3,4,5], workWindows: [{start:"08:00",end:"12:00"},{start:"13:30",end:"17:30"}], queueOutsideWorkHours: false, includeGroups: true, notificationMaxChats: 50, workOnlyNameRegex: /(^|[^A-Z0-9])JC([^A-Z0-9]|$)/i, webhookUrl: "http://n8n:5678/webhook/test", webhookSecret: "secret", dataDir: "/tmp", logLevel: "silent" };
const msg = (jid: string, id = "id", extra: Partial<IncomingMessage["key"]> = {}, message: Record<string, unknown> = { conversation: "not persisted" }): IncomingMessage => ({ key: { remoteJid: jid, id, fromMe: false, ...extra }, message });
const quiet = { info() {}, warn() {}, error() {} };
async function withState(run: (s: WatcherState, dir: string) => Promise<void> | void): Promise<void> { const dir=mkdtempSync(join(tmpdir(),"wa-test-")); const s=new WatcherState(dir); try { await run(s,dir); } finally { s.close(); rmSync(dir,{recursive:true,force:true}); } }

test("notify only: rejects append, status, newsletter, protocol and sent-by-me messages", () => {
  assert.equal(shouldProcessUpsert("append"), false); assert.equal(shouldProcessUpsert("notify"), true);
  assert.equal(isEligibleMessage(msg("status@broadcast"), true), false); assert.equal(isEligibleMessage(msg("abc@newsletter"), true), false);
  assert.equal(isEligibleMessage(msg("123@s.whatsapp.net","x",{fromMe:true}), true), false);
  assert.equal(isEligibleMessage(msg("123@s.whatsapp.net","x",{}, {protocolMessage:{}}), true), false);
});
test("direct chats and configured groups pass message filters", () => {
  assert.equal(isEligibleMessage(msg("123@s.whatsapp.net"), true), true);
  assert.equal(isEligibleMessage(msg("123@g.us"), true), true); assert.equal(isEligibleMessage(msg("123@g.us"), false), false);
});
test("work windows include start and exclude end; weekends are closed", () => {
  const at=(s:string)=>new Date(s);
  for (const [time, allowed] of [["2026-09-25T11:00:00Z",true],["2026-09-25T14:59:00Z",true],["2026-09-25T15:00:00Z",false],["2026-09-25T16:30:00Z",true],["2026-09-25T20:29:00Z",true],["2026-09-25T20:30:00Z",false],["2026-09-26T14:00:00Z",false],["2026-09-27T14:00:00Z",false]] as const) assert.equal(isWithinWorkHours(at(time),config),allowed,time);
  assert.equal(localIso(at("2026-09-25T13:37:00Z"),config.timezone),"2026-09-25T10:37:00-03:00");
});
test("normalizes and classifies standalone JC tokens without digit or letter false positives", () => {
  for (const name of ["JC", "jc", "JC Sistemas", "JC Sistema", "Grupo JC", "JC - Suporte", "Suporte JC Sistemas", " Jc   Sistemas ", "JÇ"]) assert.equal(classifyNotificationPolicy(name), "WORK_ONLY", name);
  for (const name of ["JCPneus", "JCSistemas", "Project", "Jackson", "João", "5514991234567 JC", "123JC@s.whatsapp.net", null, undefined, ""]) assert.equal(classifyNotificationPolicy(name), "ANYTIME", String(name));
});
test("rejects an invalid configurable work-only name regex", () => {
  assert.throws(() => loadConfig({ N8N_WEBHOOK_URL:"http://n8n:5678/hook", N8N_WEBHOOK_SECRET:"secret", WORK_ONLY_NAME_REGEX:"[" }), /WORK_ONLY_NAME_REGEX/);
});
test("migrates schema, loads archive state, tracks archive changes and removes deleted chats", () => withState((s) => {
  s.applyChats([{id:"a@s.whatsapp.net",archived:true},{id:"g@g.us",archived:false}]);
  assert.equal(s.getChat("a@s.whatsapp.net")?.archived,1); assert.equal(s.countArchived(),1);
  s.applyChat({jid:"g@g.us",archived:true}); assert.equal(s.getChat("g@g.us")?.archived,1);
  s.applyChat({jid:"g@g.us",archived:false}); assert.equal(s.getChat("g@g.us")?.archived,0);
  s.deleteChat("a@s.whatsapp.net"); assert.equal(s.getChat("a@s.whatsapp.net"),undefined);
}));
test("chat policy follows direct and group display-name changes", () => withState((s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true,displayName:"João"});
  assert.equal(s.getChat("a@s.whatsapp.net")?.notificationPolicy,"ANYTIME");
  s.updateContact("a@s.whatsapp.net","João - JC");
  assert.equal(s.getChat("a@s.whatsapp.net")?.notificationPolicy,"WORK_ONLY");
  s.updateContact("a@s.whatsapp.net","João");
  assert.equal(s.getChat("a@s.whatsapp.net")?.notificationPolicy,"ANYTIME");
  s.applyChat({jid:"123@g.us",archived:true,displayName:"Grupo JC",isGroup:true});
  assert.equal(s.getChat("123@g.us")?.notificationPolicy,"WORK_ONLY");
}));
test("LID to PN message mapping persists readable identity and wa.me fields", () => withState((s) => {
  s.applyChat({jid:"140763375603814@lid",archived:true,displayName:"JC Sistemas"});
  persistMessageIdentity(msg("140763375603814@lid","lid-pn",{remoteJidAlt:"5514999991234@s.whatsapp.net"}),s);
  const resolved=s.resolveIdentity("140763375603814@lid");
  assert.equal(resolved.displayName,"JC Sistemas"); assert.equal(resolved.phoneJid,"5514999991234@s.whatsapp.net"); assert.equal(resolved.phoneNumber,"5514999991234"); assert.equal(resolved.lidJid,"140763375603814@lid");
}));
test("PN to LID mapping resolves an existing contact through one canonical identity", () => withState((s) => {
  s.applyChat({jid:"5514999991234@s.whatsapp.net",archived:true,displayName:"Readable Contact"});
  persistMessageIdentity(msg("5514999991234@s.whatsapp.net","pn-lid",{remoteJidAlt:"140763375603814@lid"}),s);
  const resolved=s.resolveIdentity("140763375603814@lid");
  assert.equal(resolved.canonicalJid,"5514999991234@s.whatsapp.net"); assert.equal(resolved.displayName,"Readable Contact"); assert.equal(resolved.phoneNumber,"5514999991234");
}));
test("linking existing LID and PN rows merges pending counters into one notification", () => withState((s) => {
  s.applyChat({jid:"140763375603814@lid",archived:true,displayName:"JC Sistemas"});
  s.applyChat({jid:"5514999991234@s.whatsapp.net",archived:true,displayName:"JC Sistemas"});
  s.recordIncoming("140763375603814@lid","lid-message","2026-09-25T10:00:00-03:00");
  s.recordIncoming("5514999991234@s.whatsapp.net","pn-message","2026-09-25T10:01:00-03:00");
  persistMessageIdentity(msg("140763375603814@lid","link-existing",{remoteJidAlt:"5514999991234@s.whatsapp.net"}),s);
  assert.equal(s.getPending().length,1); assert.equal(s.getPending()[0].messageCount,2); assert.equal(s.getChat("5514999991234@s.whatsapp.net")?.jid,"140763375603814@lid"); assert.equal(s.identityCount(),1);
}));
test("LID only never creates a phone number", () => withState((s) => {
  s.applyChat({jid:"69209740566608@lid",archived:true,displayName:"Amazon"});
  s.observeIdentity({preferredJid:"69209740566608@lid",lidJid:"69209740566608@lid"});
  const resolved=s.resolveIdentity("69209740566608@lid");
  assert.equal(resolved.displayName,"Amazon"); assert.equal(resolved.phoneJid,undefined); assert.equal(resolved.phoneNumber,undefined);
}));
test("technical identifiers cannot replace a known name and raw stored LIDs are repaired", () => withState((s) => {
  s.applyChat({jid:"140763375603814@lid",archived:true,displayName:"JC Sistemas"});
  s.observeIdentity({preferredJid:"140763375603814@lid",lidJid:"140763375603814@lid",phoneJid:"5514999991234@s.whatsapp.net",username:"jc_sistemas",displayName:"140763375603814@lid",displayNameSource:"contact_name"});
  assert.equal(s.resolveIdentity("5514999991234@s.whatsapp.net").displayName,"JC Sistemas");
  s.applyChat({jid:"69209740566608@lid",archived:true,displayName:"69209740566608@lid"});
  s.observeIdentity({preferredJid:"69209740566608@lid",lidJid:"69209740566608@lid",phoneJid:"5511999990000@s.whatsapp.net",displayName:"Amazon",displayNameSource:"contact_name"});
  assert.equal(s.resolveIdentity("69209740566608@lid").displayName,"Amazon");
}));
test("username fallback is clean and replaceable by a human-readable name", () => withState((s) => {
  s.applyChat({jid:"@amorsaudelins",archived:true,displayName:"@amorsaudelins"});
  s.observeIdentity({preferredJid:"@amorsaudelins",username:"amorsaudelins"});
  assert.equal(s.resolveIdentity("@amorsaudelins").displayName,"amorsaudelins");
  s.observeIdentity({preferredJid:"@amorsaudelins",username:"amorsaudelins",displayName:"AmorSaúde Lins",displayNameSource:"notify"});
  assert.equal(s.resolveIdentity("@amorsaudelins").displayName,"AmorSaúde Lins");
}));
test("identity aliases survive restart without duplicate profiles", () => {
  const dir=mkdtempSync(join(tmpdir(),"wa-restart-"));
  const first=new WatcherState(dir); first.applyChat({jid:"140763375603814@lid",archived:true,displayName:"JC Sistemas"}); first.observeIdentity({preferredJid:"140763375603814@lid",lidJid:"140763375603814@lid",phoneJid:"5514999991234@s.whatsapp.net"}); first.close();
  const restarted=new WatcherState(dir); assert.equal(restarted.resolveIdentity("5514999991234@s.whatsapp.net").displayName,"JC Sistemas"); assert.equal(restarted.identityCount(),1); restarted.close(); rmSync(dir,{recursive:true,force:true});
});
test("identity migration preserves archived and pending counters", () => {
  const dir=mkdtempSync(join(tmpdir(),"wa-migrate-")); const db=new Database(join(dir,"watcher.db"));
  db.exec("CREATE TABLE chat_state(jid TEXT PRIMARY KEY,archived INTEGER NOT NULL DEFAULT 0,display_name TEXT NULL,is_group INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL); CREATE TABLE pending_notification(jid TEXT PRIMARY KEY,message_count INTEGER NOT NULL DEFAULT 0,first_message_at TEXT NOT NULL,last_message_at TEXT NOT NULL); INSERT INTO chat_state VALUES('123@s.whatsapp.net',1,'Readable',0,'2026-01-01T00:00:00.000Z'); INSERT INTO pending_notification VALUES('123@s.whatsapp.net',7,'a','z')"); db.close();
  const legacy=new Database(join(dir,"watcher.db")); legacy.prepare("INSERT INTO chat_state VALUES(?,?,?,?,?)").run("69209740566608@lid",1,"Amazon",0,"2026-01-01T00:00:00.000Z"); legacy.prepare("INSERT INTO chat_state VALUES(?,?,?,?,?)").run("@amorsaudelins",1,"@amorsaudelins",0,"2026-01-01T00:00:00.000Z"); legacy.close();
  const s=new WatcherState(dir); assert.equal(s.getChat("123@s.whatsapp.net")?.archived,1); assert.equal(s.getPending()[0].messageCount,7); assert.equal(s.getPending()[0].lastMessagePreview,null); assert.equal(s.resolveIdentity("123@s.whatsapp.net").displayName,"Readable"); assert.equal(s.resolveIdentity("69209740566608@lid").displayName,"Amazon"); assert.equal(s.resolveIdentity("69209740566608@lid").phoneNumber,undefined); assert.equal(s.resolveIdentity("@amorsaudelins").displayName,"amorsaudelins"); s.close(); rmSync(dir,{recursive:true,force:true});
});
test("records only archived, known eligible chats; increments and deduplicates identity", () => withState((s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true,displayName:"Contact"}); s.applyChat({jid:"b@s.whatsapp.net",archived:false}); s.applyChat({jid:"g@g.us",archived:true,isGroup:true});
  assert.equal(s.getChat("unknown@s.whatsapp.net"),undefined);
  assert.equal(s.recordIncoming("a@s.whatsapp.net","a\nid-1","2026-09-25T13:00:00-03:00"),true);
  assert.equal(s.recordIncoming("a@s.whatsapp.net","a\nid-1","2026-09-25T13:00:00-03:00"),false);
  assert.equal(s.recordIncoming("a@s.whatsapp.net","a\nid-2","2026-09-25T13:01:00-03:00"),true);
  assert.equal(s.recordIncoming("g@g.us","g\nid-3","2026-09-25T13:01:00-03:00"),true);
  const rows=s.getPending(); assert.deepEqual(rows.map(r=>[r.jid,r.messageCount]),[["a@s.whatsapp.net",2],["g@g.us",1]]);
  assert.equal(rows[0].displayName,"Contact");
}));
test("notify handling queues archived messages regardless of work hours", () => withState((s) => {
  const now=new Date("2026-09-25T13:00:00Z");
  s.applyChat({jid:"archived@s.whatsapp.net",archived:true}); s.applyChat({jid:"open@s.whatsapp.net",archived:false}); s.applyChat({jid:"g@g.us",archived:true,isGroup:true});
  recordNotifyMessages([msg("archived@s.whatsapp.net","ok"),msg("open@s.whatsapp.net","open"),msg("unknown@s.whatsapp.net","unknown"),msg("g@g.us","group"),msg("archived@s.whatsapp.net","self",{fromMe:true}),msg("status@broadcast","status"),msg("archived@s.whatsapp.net","protocol",{}, {protocolMessage:{}})],s,config,quiet,()=>now);
  assert.deepEqual(s.getPending().map(r=>[r.jid,r.messageCount]),[["archived@s.whatsapp.net",1],["g@g.us",1]]);
  assert.equal(s.getPending().find(r=>r.jid==="archived@s.whatsapp.net")?.lastMessagePreview,"not persisted");
  recordNotifyMessages([msg("g@g.us","group-disabled")],s,{...config,includeGroups:false},quiet,()=>now);
  recordNotifyMessages([msg("archived@s.whatsapp.net","outside")],s,config,quiet,()=>new Date("2026-09-25T21:00:00Z"));
  assert.equal(s.getPending().find(r=>r.jid==="g@g.us")?.messageCount,1); assert.equal(s.getPending().find(r=>r.jid==="archived@s.whatsapp.net")?.messageCount,2);
}));
test("outside hours sends ANYTIME only, retains WORK_ONLY, and clears sent snapshots", () => withState(async (s) => {
  s.applyChat({jid:"5514999991234@s.whatsapp.net",archived:true,displayName:"João"}); s.applyChat({jid:"b@g.us",archived:true,displayName:"Grupo JC"});
  s.observeIdentity({preferredJid:"5514999991234@s.whatsapp.net",lidJid:"140763375603814@lid",phoneJid:"5514999991234@s.whatsapp.net",username:"joao"});
  s.recordIncoming("5514999991234@s.whatsapp.net","a1","2026-09-25T10:00:00-03:00"); s.recordIncoming("b@g.us","b1","2026-09-25T10:01:00-03:00");
  let body:any; const n=new Notifier(config,s,quiet,async(_u,i)=>{body=JSON.parse(String(i?.body));return new Response(null,{status:204});},()=>new Date("2026-09-25T21:00:00Z"));
  await n.notifyPending(); assert.equal(body.totalChats,1); assert.equal(body.totalMessages,1); assert.equal(body.chats[0].jid,"5514999991234@s.whatsapp.net"); assert.equal(body.chats[0].displayName,"João"); assert.equal(body.chats[0].policy,"ANYTIME"); assert.equal(body.chats[0].phoneNumber,"5514999991234"); assert.equal(body.chats[0].phoneJid,"5514999991234@s.whatsapp.net"); assert.equal(body.chats[0].lidJid,"140763375603814@lid"); assert.equal(body.chats[0].remoteJidAlt,"140763375603814@lid"); assert.match(body.chats[0].firstMessageAt,/-03:00$/); assert.equal("message" in body.chats[0],false); assert.deepEqual(s.getPending().map(c=>c.jid),["b@g.us"]);
  const inside=new Notifier(config,s,quiet,async(_u,i)=>{body=JSON.parse(String(i?.body));return new Response(null,{status:204});},()=>new Date("2026-09-25T13:37:00Z"));
  await inside.notifyPending(); assert.equal(body.totalChats,1); assert.equal(body.chats[0].policy,"WORK_ONLY"); assert.equal(body.chats[0].isGroup,true); assert.equal("phoneNumber" in body.chats[0],false); assert.equal(s.getPending().length,0);
}));
test("new message during HTTP request survives snapshot clearing", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true}); s.recordIncoming("a@s.whatsapp.net","a1","2026-09-25T10:00:00-03:00");
  const n=new Notifier(config,s,quiet,async()=>{s.recordIncoming("a@s.whatsapp.net","a2","2026-09-25T10:01:00-03:00");return new Response(null,{status:200});},()=>new Date("2026-09-25T13:37:00Z"));
  await n.notifyPending(); assert.equal(s.getPending()[0].messageCount,2);
}));
test("marking an archived chat read removes its queued digest before flush", () => withState(async (s) => {
  s.applyChat({jid:"read@s.whatsapp.net",archived:true,displayName:"Read contact"});
  s.recordIncoming("read@s.whatsapp.net","incoming-1","2026-09-25T10:00:00Z");
  assert.equal(clearReadNotificationQueues({chatsUpdate:[{id:"read@s.whatsapp.net",unreadCount:0}]},s,quiet),1);
  let requests=0; await new Notifier(config,s,quiet,async()=>{requests++;return new Response(null,{status:204});}).notifyPending();
  assert.equal(requests,0); assert.equal(s.getPending().length,0);
  assert.equal(s.recordIncoming("read@s.whatsapp.net","incoming-2","2026-09-25T10:01:00Z"),true);
  assert.equal(s.getPending()[0].messageCount,1);
}));
test("message READ update clears the canonical chat queue but sent-message status does not", () => withState((s) => {
  s.applyChat({jid:"140763375603814@lid",archived:true,displayName:"Contact"});
  s.observeIdentity({preferredJid:"140763375603814@lid",lidJid:"140763375603814@lid",phoneJid:"5514999991234@s.whatsapp.net"});
  s.recordIncoming("140763375603814@lid","incoming-1");
  assert.equal(clearReadNotificationQueues({messagesUpdate:[{key:{remoteJid:"5514999991234@s.whatsapp.net",fromMe:true},update:{status:WAMessageStatus.READ}}]},s,quiet),0);
  assert.equal(s.getPending().length,1);
  assert.equal(clearReadNotificationQueues({messagesUpdate:[{key:{remoteJid:"5514999991234@s.whatsapp.net",fromMe:false},update:{status:WAMessageStatus.READ}}]},s,quiet),1);
  assert.equal(s.getPending().length,0);
}));
test("zero unread count clears direct and group pending rows, positive or missing count preserves them", () => withState((s) => {
  s.applyChat({jid:"direct@s.whatsapp.net",archived:true}); s.applyChat({jid:"group@g.us",archived:true,isGroup:true});
  s.recordIncoming("direct@s.whatsapp.net","direct-1"); s.recordIncoming("group@g.us","group-1");
  assert.equal(clearReadNotificationQueues({chatsUpdate:[{id:"direct@s.whatsapp.net",unreadCount:2},{id:"group@g.us"}]},s,quiet),0);
  assert.equal(s.getPending().length,2);
  assert.equal(clearReadNotificationQueues({chatsUpdate:[{id:"direct@s.whatsapp.net",unreadCount:0},{id:"group@g.us",unreadCount:0}]},s,quiet),2);
  assert.equal(s.getPending().length,0);
}));
test("pending archived message is removed when its chat becomes unarchived", () => withState((s,dir) => {
  s.applyChat({jid:"archived@s.whatsapp.net",archived:true});
  s.recordIncoming("archived@s.whatsapp.net","one","2026-09-25T10:00:00Z");
  s.applyChat({jid:"archived@s.whatsapp.net",archived:false});
  assert.equal(s.getPending().length,0);
  const db=new Database(join(dir,"watcher.db")); assert.equal((db.prepare("SELECT count(*) n FROM pending_notification").get() as {n:number}).n,0); db.close();
}));
test("unarchived chats cannot enqueue or flush stale pending notifications", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:false});
  assert.equal(s.recordIncoming("a@s.whatsapp.net","a1"),false);
  s.applyChat({jid:"a@s.whatsapp.net",archived:true});
  s.recordIncoming("a@s.whatsapp.net","a2");
  s.applyChat({jid:"a@s.whatsapp.net",archived:false});
  let sent=0; await new Notifier(config,s,quiet,async()=>{sent++;return new Response(null,{status:200});}).notifyPending();
  assert.equal(sent,0); assert.equal(s.getPending().length,0);
}));
test("LID and PN aliases cannot bypass current archived state and rearchive starts fresh", () => withState((s) => {
  s.applyChat({jid:"140763375603814@lid",archived:true,displayName:"Contact"});
  s.observeIdentity({preferredJid:"140763375603814@lid",lidJid:"140763375603814@lid",phoneJid:"5514999991234@s.whatsapp.net"});
  s.recordIncoming("140763375603814@lid","old");
  s.applyChat({jid:"5514999991234@s.whatsapp.net",archived:false});
  assert.equal(s.getPending().length,0);
  assert.equal(s.recordIncoming("140763375603814@lid","while-open"),false);
  s.applyChat({jid:"140763375603814@lid",archived:true});
  assert.equal(s.getPending().length,0);
  assert.equal(s.recordIncoming("5514999991234@s.whatsapp.net","new"),true);
  assert.equal(s.getPending().length,1);
}));
test("identity merge keeps the latest unarchived alias state and drops its pending messages", () => withState((s) => {
  s.applyChat({jid:"16600233177299@lid",archived:true,displayName:"Abigail Geovana"},"2026-09-28T12:00:00.000Z");
  s.recordIncoming("16600233177299@lid","old-pending","2026-09-28T12:01:00.000Z");
  s.applyChat({jid:"5511999988776@s.whatsapp.net",archived:false},"2026-09-28T12:02:00.000Z");
  s.observeIdentity({preferredJid:"16600233177299@lid",lidJid:"16600233177299@lid",phoneJid:"5511999988776@s.whatsapp.net"});
  assert.equal(s.getChat("16600233177299@lid")?.archived,0);
  assert.equal(s.getChat("5511999988776@s.whatsapp.net")?.archived,0);
  assert.equal(s.getPending().length,0);
  assert.equal(s.recordIncoming("5511999988776@s.whatsapp.net","after-unarchive"),false);
}));
test("message preview extracts text, captions, fallbacks, whitespace and truncates", () => {
  assert.equal(extractMessagePreview({conversation:" hello\n  there "}),"hello there");
  assert.equal(extractMessagePreview({extendedTextMessage:{text:"extended text"}}),"extended text");
  assert.equal(extractMessagePreview({imageMessage:{caption:"image caption"}}),"image caption");
  assert.equal(extractMessagePreview({videoMessage:{caption:"video caption"}}),"video caption");
  assert.equal(extractMessagePreview({documentMessage:{caption:"document caption"}}),"document caption");
  assert.equal(extractMessagePreview({audioMessage:{}}),"[Audio]");
  assert.equal(extractMessagePreview({audioMessage:{ptt:true}}),"[Voice message]");
  assert.equal(extractMessagePreview({stickerMessage:{}}),"[Sticker]");
  assert.equal(extractMessagePreview({contactMessage:{}}),"[Contact]");
  assert.equal(extractMessagePreview({locationMessage:{}}),"[Location]");
  assert.equal(extractMessagePreview({ephemeralMessage:{message:{viewOnceMessage:{message:{conversation:"wrapped text"}}}}}),"wrapped text");
  assert.equal(extractMessagePreview({conversation:"x".repeat(200)}).length,140);
});
test("latest incoming message replaces the persisted preview and is sent to the webhook", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true,displayName:"Contact"});
  s.recordIncoming("a@s.whatsapp.net","a1","2026-09-25T10:00:00Z",extractMessagePreview({conversation:"first"}));
  s.recordIncoming("a@s.whatsapp.net","a2","2026-09-25T10:01:00Z",extractMessagePreview({extendedTextMessage:{text:"latest message"}}));
  let body:any; await new Notifier(config,s,quiet,async(_u,i)=>{body=JSON.parse(String(i?.body));return new Response(null,{status:200});}).notifyPending();
  assert.equal(body.eventType,"archived_messages"); assert.equal(body.chats[0].lastMessagePreview,"latest message");
}));
test("short reconnects do not alert; sustained disconnect alerts once and reconnect resets the guard", async () => {
  const sent:any[]=[]; const Guard:any=ConnectionOutageGuard; const guard=new Guard((event:any)=>{sent.push(event);},undefined,undefined,20);
  guard.connectionOpened();
  guard.connectionLost({reason:"socket closed",statusCode:503,manualPairingRequired:false});
  guard.connectionLost({reason:"retry",statusCode:503,manualPairingRequired:false});
  guard.connectionOpened();
  await new Promise((resolve)=>setImmediate(resolve));
  assert.equal(sent.length,0);
  guard.connectionLost({reason:"socket closed",statusCode:503,manualPairingRequired:false});
  await new Promise((resolve)=>setTimeout(resolve,20));
  assert.equal(sent.length,1);
  guard.connectionLost({reason:"retry",statusCode:503,manualPairingRequired:false});
  guard.connectionOpened(); guard.connectionLost({reason:"logged out",statusCode:401,manualPairingRequired:true});
  await new Promise((resolve)=>setImmediate(resolve));
  assert.equal(sent.length,2); assert.equal(sent[1].manualPairingRequired,true); assert.equal(sent[1].eventType,"whatsapp_connection_lost");
});
test("failed connection alert does not block reconnect scheduling", async () => {
  let scheduled=false; const Guard:any=ConnectionOutageGuard; const guard=new Guard(async()=>{throw new Error("webhook down");},{warn(){}},()=>new Date(),1);
  guard.connectionOpened();
  guard.connectionLost({reason:"closed",statusCode:503,manualPairingRequired:false});
  scheduled=true; await new Promise((resolve)=>setImmediate(resolve)); assert.equal(scheduled,true);
});
test("connection-lost webhook uses the existing secret and explicit event payload", async () => {
  let body:any; const n=new Notifier(config,{} as WatcherState,quiet,async(_u,i)=>{body=JSON.parse(String(i?.body));return new Response(null,{status:204});});
  await n.notifyConnectionLost({eventType:"whatsapp_connection_lost",generatedAt:"2026-09-25T10:00:00.000Z",reason:"logged_out",statusCode:401,manualPairingRequired:true});
  assert.equal(body.secret,config.webhookSecret); assert.equal(body.eventType,"whatsapp_connection_lost"); assert.equal(body.reason,"logged_out"); assert.equal(body.statusCode,401); assert.equal(body.manualPairingRequired,true);
});
test("outside hours skip flush and failed request leaves pending", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true}); s.recordIncoming("a@s.whatsapp.net","a1");
  const outside=new Notifier(config,s,quiet,async()=>new Response(null,{status:404}),()=>new Date("2026-09-25T21:00:00Z")); await outside.notifyPending(); assert.equal(s.getPending().length,1);
  const failed=new Notifier({...config,queueOutsideWorkHours:true},s,quiet,async()=>new Response(null,{status:404}),()=>new Date("2026-09-25T13:37:00Z")); await failed.notifyPending(); assert.equal(s.getPending().length,1);
}));
