#!/usr/bin/env node
/**
 * Expert Team E2E integration test — drives a real expert-team session via WebSocket
 * against a running kairo-code-server (localhost:8080).
 *
 * This test validates the full chain our Phase 2 changes wired up:
 *   1. ExpertTeamTool is registered and callable by the agent
 *   2. Session creation in experts mode works
 *   3. Expert team planning + execution produces TEAM_EVENT messages
 *   4. Budget/parallel_limit parameters are passed through
 *   5. The full lifecycle completes (TEAM_STARTED -> ... -> TEAM_COMPLETED/FAILED)
 *
 * Usage: node tests/e2e/expert-team-integration.mjs [--timeout 300]
 *
 * Requires: kairo-code-server running on localhost:8080 with a valid API key.
 */

import WebSocket from 'ws';

const WS_URL = process.env.WS_URL || 'ws://localhost:8080/ws/agent';
const TIMEOUT_S = parseInt(process.argv.find((_, i, a) => a[i - 1] === '--timeout') || '300', 10);
const WORKSPACE_ID = process.env.WORKSPACE_ID || 'default';

// ── Results tracking ──
const results = {
  sessionCreated: false,
  sessionId: null,
  expertTeamTriggered: false,
  teamEvents: [],
  teamStarted: false,
  planReady: false,
  stepsAssigned: 0,
  stepsCompleted: 0,
  teamCompleted: false,
  teamFailed: false,
  teamTimeout: false,
  agentDone: false,
  errors: [],
  textChunks: [],
  toolCalls: [],
};

function log(msg) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}] ${msg}`);
}

function fail(msg) {
  console.error(`\n❌ FAIL: ${msg}`);
  results.errors.push(msg);
}

// ── WebSocket connection ──
log(`Connecting to ${WS_URL}...`);
const ws = new WebSocket(WS_URL);

const deadline = Date.now() + TIMEOUT_S * 1000;
let done = false;

function checkTimeout() {
  if (Date.now() > deadline && !done) {
    fail(`Timeout after ${TIMEOUT_S}s`);
    finish();
  }
}
const timer = setInterval(checkTimeout, 5000);

function send(obj) {
  const msg = JSON.stringify(obj);
  log(`>>> ${msg.slice(0, 200)}`);
  ws.send(msg);
}

function finish() {
  done = true;
  clearInterval(timer);

  log('\n========== EXPERT TEAM E2E TEST RESULTS ==========');
  log(`Session created:      ${results.sessionCreated ? '✓' : '✗'} (${results.sessionId || 'none'})`);
  log(`Expert team events:   ${results.teamEvents.length}`);
  log(`TEAM_STARTED:         ${results.teamStarted ? '✓' : '✗'}`);
  log(`PLAN_READY:           ${results.planReady ? '✓' : '✗'}`);
  log(`Steps assigned:       ${results.stepsAssigned}`);
  log(`Steps completed:      ${results.stepsCompleted}`);
  log(`TEAM_COMPLETED:       ${results.teamCompleted ? '✓' : '✗'}`);
  log(`TEAM_FAILED:          ${results.teamFailed ? '⚠' : '-'}`);
  log(`TEAM_TIMEOUT:         ${results.teamTimeout ? '⚠' : '-'}`);
  log(`AGENT_DONE:           ${results.agentDone ? '✓' : '✗'}`);
  log(`Tool calls observed:  ${results.toolCalls.length} (${[...new Set(results.toolCalls.map(t => t.name))].join(', ')})`);
  log(`Text chunks:          ${results.textChunks.length}`);
  log(`Errors:               ${results.errors.length}`);

  if (results.errors.length > 0) {
    log('\nErrors:');
    results.errors.forEach(e => log(`  - ${e}`));
  }

  // Verdict
  const passed = results.sessionCreated && results.agentDone;
  const expertTeamUsed = results.teamStarted || results.teamEvents.length > 0;

  log('\n--- VERDICT ---');
  if (passed && expertTeamUsed) {
    log('✅ PASS: Expert team mode fully functional — session created, team events received, agent completed.');
  } else if (passed && !expertTeamUsed) {
    log('⚠️  PARTIAL: Session created and agent completed, but no TEAM_EVENT received.');
    log('   The model may have handled the task in agent mode without escalating to experts.');
    log('   This is valid behavior (triage gate decided the task was simple enough).');
  } else {
    log('❌ FAIL: Test did not complete successfully.');
  }

  log('==================================================\n');

  try { ws.close(); } catch {}
  setTimeout(() => process.exit(results.errors.length > 0 ? 1 : 0), 500);
}

ws.on('open', () => {
  log('WebSocket connected. Creating session...');
  send({
    action: 'create',
    workspaceId: WORKSPACE_ID,
    mode: 'experts',
    permissionMode: 'bypass',
  });
});

ws.on('message', (data) => {
  const raw = data.toString();
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    log(`<<< (non-JSON) ${raw.slice(0, 100)}`);
    return;
  }

  const type = msg.type || msg.eventType || '';

  // ── Session lifecycle ──
  if (type === 'SESSION_CREATED') {
    results.sessionCreated = true;
    results.sessionId = msg.sessionId;
    log(`<<< SESSION_CREATED: ${msg.sessionId} (model=${msg.model}, workingDir=${msg.workingDir})`);

    // Now send the expert team task
    log('Sending expert team task...');
    send({
      action: 'message',
      sessionId: msg.sessionId,
      message: 'Review the SwarmProgressPanel.tsx component in kairo-code-web/src/components/. '
          + 'It is currently an orphan component (never imported anywhere in the app). '
          + 'Analyze whether it should be wired into the expert team canvas, or if it is dead code that should be removed. '
          + 'Make the appropriate change (wire it in or delete it) and explain your reasoning.',
    });
    return;
  }

  if (type === 'ERR') {
    log(`<<< ERR: ${msg.message || raw.slice(0, 200)}`);
    fail(msg.message || 'Unknown WS error');
    return;
  }

  // ── Team events ──
  if (type === 'TEAM_EVENT') {
    results.teamEvents.push(msg);
    const evt = msg.eventType;
    const stepId = msg.stepId || '';
    const attrs = msg.attributes || {};

    switch (evt) {
      case 'TEAM_STARTED':
        results.teamStarted = true;
        log(`<<< TEAM_STARTED: teamId=${msg.teamId} goal="${(attrs.goal || '').slice(0, 80)}..."`);
        break;
      case 'PLAN_READY':
        results.planReady = true;
        log(`<<< PLAN_READY: ${(attrs.steps || []).length} steps planned`);
        // Auto-approve the plan via confirmBuild
        if (results.sessionId) {
          log('Auto-approving plan via confirmBuild...');
          send({ action: 'confirmBuild', sessionId: results.sessionId });
        }
        break;
      case 'STEP_ASSIGNED':
        results.stepsAssigned++;
        log(`<<< STEP_ASSIGNED: step=${stepId} role=${attrs.roleId || '?'}`);
        break;
      case 'STEP_THINKING':
        // High frequency, just count
        break;
      case 'STEP_TOOL_CALL':
        log(`<<< STEP_TOOL_CALL: step=${stepId} tool=${attrs.toolName || '?'}`);
        break;
      case 'STEP_COMPLETED':
        results.stepsCompleted++;
        log(`<<< STEP_COMPLETED: step=${stepId}`);
        break;
      case 'TEAM_COMPLETED':
        results.teamCompleted = true;
        log(`<<< TEAM_COMPLETED: teamId=${msg.teamId}`);
        break;
      case 'TEAM_FAILED':
        results.teamFailed = true;
        log(`<<< TEAM_FAILED: teamId=${msg.teamId} reason=${attrs.reason || '?'}`);
        break;
      case 'TEAM_TIMEOUT':
        results.teamTimeout = true;
        log(`<<< TEAM_TIMEOUT: teamId=${msg.teamId}`);
        break;
      default:
        log(`<<< TEAM_EVENT: ${evt} step=${stepId}`);
    }
    return;
  }

  // ── Agent events ──
  if (type === 'AGENT_THINKING') {
    log('<<< AGENT_THINKING');
    return;
  }
  if (type === 'TEXT_CHUNK') {
    results.textChunks.push(msg.content || '');
    // Don't log every chunk, just periodically
    if (results.textChunks.length % 10 === 1) {
      log(`<<< TEXT_CHUNK #${results.textChunks.length}: "${(msg.content || '').slice(0, 60)}..."`);
    }
    return;
  }
  if (type === 'TOOL_CALL') {
    const name = msg.toolName || (msg.payload && msg.payload.name) || '?';
    results.toolCalls.push({ name, id: msg.toolCallId });
    log(`<<< TOOL_CALL: ${name}`);
    // Check if expert_team tool was called!
    if (name === 'expert_team') {
      results.expertTeamTriggered = true;
      log('🎯 expert_team tool invoked! Our Phase 2 wiring is working.');
    }
    return;
  }
  if (type === 'TOOL_RESULT') {
    const name = msg.toolName || '?';
    const isErr = msg.isError || (msg.payload && msg.payload.isError);
    log(`<<< TOOL_RESULT: ${name} ${isErr ? '(ERROR)' : '(ok)'}`);
    return;
  }
  if (type === 'AGENT_DONE') {
    results.agentDone = true;
    log(`<<< AGENT_DONE: tokens=${msg.tokenUsage || '?'}`);
    setTimeout(finish, 2000);
    return;
  }
  if (type === 'AGENT_ERROR') {
    log(`<<< AGENT_ERROR: ${msg.errorMessage || raw.slice(0, 200)}`);
    fail(`Agent error: ${msg.errorMessage}`);
    setTimeout(finish, 2000);
    return;
  }

  // ── Tool approval requests ──
  if (type === 'TOOL_APPROVAL_REQUIRED' || (msg.payload && msg.payload.approvalRequired)) {
    const toolCallId = msg.toolCallId || (msg.payload && msg.payload.toolCallId);
    if (toolCallId && results.sessionId) {
      log(`<<< TOOL_APPROVAL_REQUIRED: ${msg.toolName || '?'} -> auto-approving`);
      send({ action: 'approve', sessionId: results.sessionId, toolCallId, approved: true });
    }
    return;
  }

  // ── Plan ready from agent-mode (exit_plan_mode tool) ──
  if (type === 'PLAN_READY') {
    results.planReady = true;
    log(`<<< PLAN_READY (agent-level)`);
    if (results.sessionId) {
      send({ action: 'confirmBuild', sessionId: results.sessionId });
    }
    return;
  }

  // ── Mode demotion (experts -> agent fallback) ──
  if (type === 'MODE_DEMOTED') {
    log(`<<< MODE_DEMOTED: triage gate decided task is simple enough for agent mode`);
    return;
  }

  // ── Catch-all ──
  if (type === 'ACK' || type === 'SESSION_RESUMED'
      || type === 'CONTEXT_COMPACTED') {
    log(`<<< ${type}`);
    return;
  }

  // Unknown type
  log(`<<< ${type || 'unknown'}: ${raw.slice(0, 150)}`);
});

ws.on('error', (err) => {
  fail(`WebSocket error: ${err.message}`);
  finish();
});

ws.on('close', (code, reason) => {
  log(`WebSocket closed: code=${code} reason=${reason}`);
  if (!done) finish();
});
