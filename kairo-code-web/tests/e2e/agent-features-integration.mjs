#!/usr/bin/env node
/**
 * Agent-mode feature integration tests — triggers the 3 scenarios that need
 * real LLM execution to verify:
 *
 *   Test 1: Task tool (subagent) — explicitly asks the model to use the task tool
 *   Test 2: expert_team tool in Agent mode — explicitly invokes expert_team
 *   Test 3: FailurePatternTracker — forces 3 consecutive bash failures
 *
 * All sessions use permissionMode=bypass so tools auto-execute without approval.
 */

import WebSocket from 'ws';
import { readFileSync } from 'fs';
import { homedir } from 'os';
import { join } from 'path';

const WS_URL = process.env.WS_URL || 'ws://localhost:8080/ws/agent';
const WORKSPACE_ID = process.env.WORKSPACE_ID || '956711b3-4433-465c-ab1f-7ae33a40f94c';

function log(msg) {
  console.log(`[${new Date().toISOString().slice(11, 23)}] ${msg}`);
}

// ── Generic WS session runner ──
function runSession(testName, message, timeoutS, checkFn) {
  return new Promise((resolve) => {
    const results = {
      testName,
      sessionId: null,
      toolCalls: [],
      toolResults: [],
      textChunks: [],
      teamEvents: [],
      peerMessages: [],
      agentDone: false,
      errors: [],
    };

    log(`\n${'='.repeat(60)}`);
    log(`TEST: ${testName}`);
    log(`${'='.repeat(60)}`);

    const ws = new WebSocket(WS_URL);
    const deadline = Date.now() + timeoutS * 1000;
    let done = false;

    const timer = setInterval(() => {
      if (Date.now() > deadline && !done) {
        done = true;
        clearInterval(timer);
        results.errors.push(`Timeout after ${timeoutS}s`);
        try { ws.close(); } catch {}
        resolve(results);
      }
    }, 3000);

    function send(obj) {
      ws.send(JSON.stringify(obj));
    }

    function finish() {
      if (done) return;
      done = true;
      clearInterval(timer);
      try { ws.close(); } catch {}
      // Small delay for cleanup
      setTimeout(() => resolve(results), 500);
    }

    ws.on('open', () => {
      log('Connected. Creating session (bypass mode)...');
      send({
        action: 'create',
        workspaceId: WORKSPACE_ID,
        mode: 'agent',
        permissionMode: 'bypass',
      });
    });

    ws.on('message', (data) => {
      const raw = data.toString();
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
      const type = msg.type || '';

      if (type === 'SESSION_CREATED') {
        results.sessionId = msg.sessionId;
        log(`Session: ${msg.sessionId}`);
        log(`Sending: "${message.slice(0, 80)}..."`);
        send({ action: 'message', sessionId: msg.sessionId, message });
        return;
      }

      if (type === 'ERR') {
        log(`ERR: ${msg.message}`);
        results.errors.push(msg.message);
        return;
      }

      if (type === 'TOOL_CALL') {
        const name = msg.toolName || '?';
        results.toolCalls.push({ name, args: msg.toolInput });
        log(`TOOL_CALL: ${name}`);
        return;
      }

      if (type === 'TOOL_RESULT') {
        const name = msg.toolName || '?';
        const isErr = msg.isError;
        results.toolResults.push({ name, isError: isErr });
        log(`TOOL_RESULT: ${name} ${isErr ? '(ERROR)' : '(ok)'}`);
        return;
      }

      if (type === 'TEXT_CHUNK') {
        results.textChunks.push(msg.content || '');
        return;
      }

      if (type === 'PEER_MESSAGE') {
        results.peerMessages.push(msg.content || '');
        log(`PEER_MESSAGE: ${(msg.content || '').slice(0, 60)}`);
        return;
      }

      if (type === 'TEAM_EVENT') {
        results.teamEvents.push(msg);
        log(`TEAM_EVENT: ${msg.eventType}`);
        return;
      }

      if (type === 'PLAN_READY') {
        log('PLAN_READY -> auto-confirming');
        send({ action: 'confirmBuild', sessionId: results.sessionId });
        return;
      }

      if (type === 'AGENT_DONE') {
        results.agentDone = true;
        log(`AGENT_DONE (tokens=${msg.tokenUsage || '?'})`);
        setTimeout(finish, 1000);
        return;
      }

      if (type === 'AGENT_ERROR') {
        log(`AGENT_ERROR: ${msg.errorMessage}`);
        results.errors.push(msg.errorMessage);
        setTimeout(finish, 1000);
        return;
      }

      if (type === 'MODE_ESCALATED') {
        log('MODE_ESCALATED: task upgraded to expert team');
        return;
      }
    });

    ws.on('error', (err) => {
      results.errors.push(`WS error: ${err.message}`);
      finish();
    });

    ws.on('close', () => {
      if (!done) finish();
    });
  });
}

// ── Test 1: Task tool (subagent) ──
async function testTaskTool() {
  const r = await runSession(
    'Task Tool (Subagent)',
    'You MUST use the "task" tool to create a subtask. Create exactly one subtask with instruction: "Read the file calc.py and output its first 3 lines". Do NOT read the file yourself — delegate it via the task tool.',
    180,
  );

  const taskCalls = r.toolCalls.filter(t => t.name === 'task');
  const passed = taskCalls.length > 0;

  log(`\n--- Result ---`);
  log(`Task tool invoked: ${passed ? 'YES' : 'NO'} (${taskCalls.length} calls)`);
  log(`All tools used: ${[...new Set(r.toolCalls.map(t => t.name))].join(', ')}`);
  log(`Agent done: ${r.agentDone}`);
  log(`Verdict: ${passed ? '✅ PASS' : '❌ FAIL — task tool not invoked'}`);
  return { name: 'Task Tool', passed, details: `${taskCalls.length} task calls, tools: ${[...new Set(r.toolCalls.map(t=>t.name))].join(',')}` };
}

// ── Test 2: expert_team tool in Agent mode ──
async function testExpertTeamTool() {
  const r = await runSession(
    'expert_team Tool in Agent Mode',
    'You MUST use the "expert_team" tool to launch an expert team. Call expert_team with goal="Summarize what the calc.py file does" and timeout_minutes=2. Do NOT do the work yourself — delegate it via expert_team.',
    300,
  );

  const etCalls = r.toolCalls.filter(t => t.name === 'expert_team');
  const passed = etCalls.length > 0;

  log(`\n--- Result ---`);
  log(`expert_team tool invoked: ${passed ? 'YES' : 'NO'} (${etCalls.length} calls)`);
  log(`Team events: ${r.teamEvents.length}, Peer messages: ${r.peerMessages.length}`);
  log(`All tools used: ${[...new Set(r.toolCalls.map(t => t.name))].join(', ')}`);
  log(`Agent done: ${r.agentDone}`);
  log(`Verdict: ${passed ? '✅ PASS' : '❌ FAIL — expert_team tool not invoked'}`);
  return { name: 'expert_team Tool', passed, details: `${etCalls.length} calls, ${r.peerMessages.length} peer msgs` };
}

// ── Test 3: FailurePatternTracker (3 consecutive bash failures → lesson) ──
async function testFailureTracker() {
  const r = await runSession(
    'FailurePatternTracker (3x bash failure)',
    'Run these three bash commands one by one (do NOT combine them): 1) cat /nonexistent/path/file1.txt  2) cat /nonexistent/path/file2.txt  3) cat /nonexistent/path/file3.txt. After all three fail, tell me what happened.',
    120,
  );

  const bashResults = r.toolResults.filter(t => t.name === 'bash');
  const bashErrors = bashResults.filter(t => t.isError);

  // Check if learned.json got a new lesson
  let lessonCreated = false;
  try {
    const learned = readFileSync(join(homedir(), '.kairo-code', 'learned.json'), 'utf-8');
    const lessons = JSON.parse(learned);
    lessonCreated = Array.isArray(lessons) && lessons.length > 0;
    if (lessonCreated) {
      log(`Lessons found: ${lessons.map(l => `[${l.status}] ${l.toolName}: ${l.lessonText?.slice(0,50)}`).join('; ')}`);
    }
  } catch {
    // File may not exist yet (async write)
  }

  const passed = bashErrors.length >= 3;

  log(`\n--- Result ---`);
  log(`Bash calls: ${bashResults.length}, Bash errors: ${bashErrors.length}`);
  log(`3+ consecutive failures: ${passed ? 'YES' : 'NO'}`);
  log(`Lesson in learned.json: ${lessonCreated ? 'YES' : 'not yet (async)'}`);
  log(`All tools used: ${[...new Set(r.toolCalls.map(t => t.name))].join(', ')}`);
  log(`Verdict: ${passed ? '✅ PASS' : '❌ FAIL — need 3+ bash errors'}`);
  return { name: 'FailurePatternTracker', passed, details: `${bashErrors.length} bash errors, lesson=${lessonCreated}` };
}

// ── Run all tests ──
async function main() {
  log('Starting Agent-mode feature integration tests...\n');

  const results = [];

  // Test 3 first (fastest, doesn't need expert team boot)
  results.push(await testFailureTracker());

  // Test 1
  results.push(await testTaskTool());

  // Test 2 (slowest — expert team boot + execution)
  results.push(await testExpertTeamTool());

  // Wait a bit for async lesson write
  log('\nWaiting 5s for async lesson write...');
  await new Promise(r => setTimeout(r, 5000));

  // Re-check learned.json
  let finalLessons = [];
  try {
    finalLessons = JSON.parse(readFileSync(join(homedir(), '.kairo-code', 'learned.json'), 'utf-8'));
  } catch {}

  log(`\n${'='.repeat(60)}`);
  log('FINAL RESULTS');
  log(`${'='.repeat(60)}`);

  for (const r of results) {
    log(`  ${r.passed ? '✅' : '❌'} ${r.name}: ${r.details}`);
  }
  log(`  📝 Lessons in learned.json: ${finalLessons.length}`);
  if (finalLessons.length > 0) {
    for (const l of finalLessons) {
      log(`     [${l.status}] ${l.toolName}: ${l.lessonText?.slice(0,80)}`);
    }
  }

  const allPassed = results.every(r => r.passed);
  log(`\n${allPassed ? '✅ ALL TESTS PASSED' : '❌ SOME TESTS FAILED'}`);
  process.exit(allPassed ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(1); });
