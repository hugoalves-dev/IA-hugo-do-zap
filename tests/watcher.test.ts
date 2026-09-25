import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { classifyNotificationPolicy, isWithinWorkHours, localIso, loadConfig, type Config } from "../src/config.js";
import { isEligibleMessage, shouldProcessUpsert, type IncomingMessage } from "../src/message-filter.js";
import { Notifier } from "../src/notifier.js";
import { recordNotifyMessages } from "../src/notify-handler.js";
import { WatcherState } from "../src/watcher-state.js";

const config: Config = { timezone: "America/Sao_Paulo", checkCron: "* * * * *", workDays: [1,2,3,4,5], workWindows: [{start:"08:00",end:"12:00"},{start:"13:30",end:"17:30"}], queueOutsideWorkHours: false, includeGroups: true, notificationMaxChats: 50, workOnlyNameRegex: /(^|[^A-Z0-9])JC([^A-Z0-9]|$)/i, webhookUrl: "http://n8n:5678/webhook/test", webhookSecret: "secret", dataDir: "/tmp", logLevel: "silent" };
const msg = (jid: string, id = "id", extra: Partial<IncomingMessage["key"]> = {}, message: Record<string, unknown> = { conversation: "not persisted" }): IncomingMessage => ({ key: { remoteJid: jid, id, fromMe: false, ...extra }, message });
const quiet = { info() {}, warn() {}, error() {} };
async function withState(run: (s: WatcherState) => Promise<void> | void): Promise<void> { const dir=mkdtempSync(join(tmpdir(),"wa-test-")); const s=new WatcherState(dir); try { await run(s); } finally { s.close(); rmSync(dir,{recursive:true,force:true}); } }

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
  recordNotifyMessages([msg("g@g.us","group-disabled")],s,{...config,includeGroups:false},quiet,()=>now);
  recordNotifyMessages([msg("archived@s.whatsapp.net","outside")],s,config,quiet,()=>new Date("2026-09-25T21:00:00Z"));
  assert.equal(s.getPending().find(r=>r.jid==="g@g.us")?.messageCount,1); assert.equal(s.getPending().find(r=>r.jid==="archived@s.whatsapp.net")?.messageCount,2);
}));
test("outside hours sends ANYTIME only, retains WORK_ONLY, and clears sent snapshots", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true,displayName:"João"}); s.applyChat({jid:"b@g.us",archived:true,displayName:"Grupo JC"});
  s.recordIncoming("a@s.whatsapp.net","a1","2026-09-25T10:00:00-03:00"); s.recordIncoming("b@g.us","b1","2026-09-25T10:01:00-03:00");
  let body:any; const n=new Notifier(config,s,quiet,async(_u,i)=>{body=JSON.parse(String(i?.body));return new Response(null,{status:204});},()=>new Date("2026-09-25T21:00:00Z"));
  await n.notifyPending(); assert.equal(body.totalChats,1); assert.equal(body.totalMessages,1); assert.equal(body.chats[0].displayName,"João"); assert.equal(body.chats[0].policy,"ANYTIME"); assert.match(body.chats[0].firstMessageAt,/-03:00$/); assert.equal("message" in body.chats[0],false); assert.deepEqual(s.getPending().map(c=>c.jid),["b@g.us"]);
  const inside=new Notifier(config,s,quiet,async(_u,i)=>{body=JSON.parse(String(i?.body));return new Response(null,{status:204});},()=>new Date("2026-09-25T13:37:00Z"));
  await inside.notifyPending(); assert.equal(body.totalChats,1); assert.equal(body.chats[0].policy,"WORK_ONLY"); assert.equal(s.getPending().length,0);
}));
test("new message during HTTP request survives snapshot clearing", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true}); s.recordIncoming("a@s.whatsapp.net","a1","2026-09-25T10:00:00-03:00");
  const n=new Notifier(config,s,quiet,async()=>{s.recordIncoming("a@s.whatsapp.net","a2","2026-09-25T10:01:00-03:00");return new Response(null,{status:200});},()=>new Date("2026-09-25T13:37:00Z"));
  await n.notifyPending(); assert.equal(s.getPending()[0].messageCount,2);
}));
test("outside hours skip flush and failed request leaves pending", () => withState(async (s) => {
  s.applyChat({jid:"a@s.whatsapp.net",archived:true}); s.recordIncoming("a@s.whatsapp.net","a1");
  const outside=new Notifier(config,s,quiet,async()=>new Response(null,{status:404}),()=>new Date("2026-09-25T21:00:00Z")); await outside.notifyPending(); assert.equal(s.getPending().length,1);
  const failed=new Notifier({...config,queueOutsideWorkHours:true},s,quiet,async()=>new Response(null,{status:404}),()=>new Date("2026-09-25T13:37:00Z")); await failed.notifyPending(); assert.equal(s.getPending().length,1);
}));
