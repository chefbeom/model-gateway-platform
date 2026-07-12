#!/usr/bin/env python3
"""End-to-end external-provider scenario. Requires ADMIN_API_TOKEN and runs inside the Compose network."""
import json
import os
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread

BASE = os.getenv("AICONNECT_BASE_URL", "http://api:8080")
ADMIN = os.environ["ADMIN_API_TOKEN"]
stamp = str(int(time.time()))
results = []
mock_calls = {"models": 0, "chat": 0}


class MockOpenAiHandler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        return self.headers.get("Authorization") == "Bearer provider-secret"

    def do_GET(self):
        if not self.authorized():
            return self.reply(401, {"error": {"message": "unauthorized"}})
        if self.path == "/v1/models":
            mock_calls["models"] += 1
            return self.reply(200, {"object": "list", "data": [{"id": "vm-mock-gpt", "object": "model"}]})
        return self.reply(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if not self.authorized():
            return self.reply(401, {"error": {"message": "unauthorized"}})
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        if self.path == "/v1/chat/completions":
            mock_calls["chat"] += 1
            return self.reply(200, {
                "id": "chatcmpl-vm-scenario", "object": "chat.completion", "model": payload.get("model"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "VM external provider scenario ok"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            })
        return self.reply(404, {"error": {"message": "not found"}})

    def log_message(self, _format, *_args):
        return


mock_server = HTTPServer(("0.0.0.0", 8080), MockOpenAiHandler)
Thread(target=mock_server.serve_forever, daemon=True).start()


def call(method, path, body=None, bearer=None, admin=False):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if admin:
        headers["X-Admin-Token"] = ADMIN
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            parsed = json.loads(raw) if raw else None
        except Exception:
            parsed = {"raw": raw.decode(errors="replace")}
        return error.code, parsed


def expect(label, actual, expected):
    if actual != expected:
        raise RuntimeError(f"{label}: expected {expected}, got {actual}")
    results.append({"step": label, "status": actual, "ok": True})


code, organizations = call("GET", "/api/admin/organizations", admin=True)
expect("organization-list", code, 200)
if not organizations:
    raise RuntimeError("No organization exists on VM")
organization_id = organizations[0]["id"]

code, team = call("POST", f"/api/admin/organizations/{organization_id}/teams", {"name": "VM External Scenario " + stamp}, admin=True)
expect("team-create", code, 200)
email = f"vm-scenario-{stamp}@aiconnect.local"
password = "VmScenario!" + stamp + "Secure"
code, user = call("POST", f"/api/admin/organizations/{organization_id}/users", {
    "email": email, "password": password, "organizationRole": "DEVELOPER",
    "teamId": team["id"], "teamRole": "DEVELOPER"
}, admin=True)
expect("developer-create", code, 200)
code, project = call("POST", "/api/admin/projects", {
    "organizationId": organization_id, "teamId": team["id"], "name": "VM External Provider Scenario " + stamp
}, admin=True)
expect("project-create", code, 200)
code, login = call("POST", "/api/auth/login", {"email": email, "password": password})
expect("developer-login", code, 200)
user_token = login["accessToken"]

code, provider = call("POST", "/api/admin/external-providers", {
    "organizationId": organization_id, "displayName": "VM Mock OpenAI " + stamp,
    "baseUrl": "http://aiconnect-scenario-openai:8080/v1", "apiKey": "provider-secret"
}, admin=True)
expect("provider-register", code, 200)
if "apiKey" in provider or provider.get("apiKeyConfigured") is not True:
    raise RuntimeError("Provider secret exposure/configuration check failed")
code, probe = call("POST", f"/api/admin/external-providers/{provider['id']}/probe", admin=True)
expect("provider-probe", code, 200)
code, cloud = call("POST", f"/api/admin/external-providers/{provider['id']}/models", {
    "providerModelId": "vm-mock-gpt", "displayName": "VM Mock GPT",
    "compatibilityKey": "vm-scenario-compatible", "contextLength": 128000,
    "maxConcurrency": 8, "capabilitiesJson": "[]",
    "inputPricePerMillion": 1000, "outputPricePerMillion": 2000
}, admin=True)
expect("external-model-register", code, 200)

access_path = f"/api/admin/projects/{project['id']}/external-access/{provider['id']}"
code, direct = call("PATCH", access_path, {
    "status": "APPROVED", "manualAllowed": True, "autoFailoverEnabled": False,
    "monthlyCostLimit": 100, "expiresAt": None
}, admin=True)
expect("admin-direct-grant", code, 200)
if direct["status"] != "APPROVED" or direct["autoFailoverEnabled"] is not False:
    raise RuntimeError("Direct grant policy mismatch")

code, requested = call("POST", f"/api/portal/projects/{project['id']}/external-access", {
    "providerId": provider["id"], "reason": "Need approved external model for VM scenario"
}, bearer=user_token)
expect("user-access-request", code, 200)
if requested["status"] != "REQUESTED":
    raise RuntimeError("User request status mismatch")

manual_key = "vm-cloud-" + stamp
code, manual = call("POST", "/api/admin/services", {
    "organizationId": organization_id, "serviceKey": manual_key, "displayName": "VM Cloud Manual",
    "failoverPolicy": "COMPATIBLE", "retryPolicy": "SAFE", "allowDegraded": False,
    "requiredCapabilitiesJson": "[]", "inputPricePerMillion": 0, "outputPricePerMillion": 0
}, admin=True)
expect("manual-service-create", code, 200)
code, _ = call("POST", f"/api/admin/services/{manual['id']}/targets", {
    "deploymentId": cloud["id"], "priority": 1, "weight": 100,
    "degraded": False, "maxConcurrencyOverride": None
}, admin=True)
expect("manual-target-add", code, 200)
code, _ = call("POST", f"/api/admin/projects/{project['id']}/service-access", {"serviceId": manual["id"]}, admin=True)
expect("manual-service-access", code, 200)
code, issued = call("POST", f"/api/portal/projects/{project['id']}/api-keys", {
    "name": "vm-scenario-key", "expiresAt": None
}, bearer=user_token)
expect("developer-api-key-issue", code, 200)
api_key = issued["secret"]
manual_body = {"model": manual_key, "messages": [{"role": "user", "content": "manual external scenario"}], "stream": False}
code, _ = call("POST", "/v1/chat/completions", manual_body, bearer=api_key)
expect("manual-before-approval-blocked", code, 503)

code, approved = call("PATCH", access_path, {
    "status": "APPROVED", "manualAllowed": True, "autoFailoverEnabled": False,
    "monthlyCostLimit": 100, "expiresAt": None
}, admin=True)
expect("admin-approve-user-request", code, 200)
code, manual_response = call("POST", "/v1/chat/completions", manual_body, bearer=api_key)
expect("manual-external-success", code, 200)
if manual_response.get("model") != manual_key or manual_response["choices"][0]["message"]["content"] != "VM external provider scenario ok":
    raise RuntimeError("Manual response rewrite/content mismatch")

code, node = call("POST", "/api/admin/nodes", {
    "organizationId": organization_id, "name": "vm-offline-node-" + stamp,
    "description": "intentional offline scenario node", "connectionMode": "DIRECT", "labelsJson": "{}"
}, admin=True)
expect("offline-node-create", code, 200)
code, endpoint = call("POST", "/api/admin/runtime-endpoints", {
    "nodeId": node["id"], "runtimeType": "LM_STUDIO",
    "baseUrl": "http://vm-unreachable-" + stamp + ":1234", "apiToken": None
}, admin=True)
expect("offline-endpoint-create", code, 200)
code, local = call("POST", "/api/admin/model-deployments", {
    "runtimeEndpointId": endpoint["id"], "providerModelId": "vm-local-model",
    "compatibilityKey": "vm-scenario-compatible", "displayName": "VM Offline Local",
    "modelFamily": "scenario", "quantization": "Q4", "contextLength": 8192,
    "maxConcurrency": 1, "capabilitiesJson": "[]"
}, admin=True)
expect("offline-deployment-create", code, 200)

resilient_key = "vm-resilient-" + stamp
code, resilient = call("POST", "/api/admin/services", {
    "organizationId": organization_id, "serviceKey": resilient_key, "displayName": "VM Resilient",
    "failoverPolicy": "COMPATIBLE", "retryPolicy": "SAFE", "allowDegraded": False,
    "requiredCapabilitiesJson": "[]", "inputPricePerMillion": 0, "outputPricePerMillion": 0
}, admin=True)
expect("resilient-service-create", code, 200)
for deployment, priority in ((local, 1), (cloud, 100)):
    code, _ = call("POST", f"/api/admin/services/{resilient['id']}/targets", {
        "deploymentId": deployment["id"], "priority": priority, "weight": 100,
        "degraded": False, "maxConcurrencyOverride": None
    }, admin=True)
    expect("resilient-target-" + str(priority), code, 200)
code, _ = call("POST", f"/api/admin/projects/{project['id']}/service-access", {"serviceId": resilient["id"]}, admin=True)
expect("resilient-service-access", code, 200)
resilient_body = {"model": resilient_key, "messages": [{"role": "user", "content": "automatic failover scenario"}], "stream": False}
code, _ = call("POST", "/v1/chat/completions", resilient_body, bearer=api_key)
expect("auto-failover-off-blocked", code, 503)
code, auto = call("PATCH", access_path, {
    "status": "APPROVED", "manualAllowed": True, "autoFailoverEnabled": True,
    "monthlyCostLimit": 100, "expiresAt": None
}, admin=True)
expect("auto-failover-enable", code, 200)
code, auto_response = call("POST", "/v1/chat/completions", resilient_body, bearer=api_key)
expect("auto-failover-on-success", code, 200)
if auto_response.get("model") != resilient_key:
    raise RuntimeError("Auto response logical model mismatch")

code, page = call("GET", f"/api/admin/organizations/{organization_id}/requests?projectId={project['id']}&size=50", admin=True)
expect("admin-observability-query", code, 200)
items = page.get("items", [])
routes = {(item.get("providerType"), item.get("routingReason"), item.get("status")) for item in items}
if ("OPENAI", "MANUAL_EXTERNAL", "SUCCEEDED") not in routes or ("OPENAI", "AUTO_FAILOVER", "SUCCEEDED") not in routes:
    raise RuntimeError("Provider/routing audit records missing")
code, usage = call("GET", f"/api/portal/organizations/{organization_id}/usage-overview?projectId={project['id']}", bearer=user_token)
expect("developer-usage-query", code, 200)
usage_text = json.dumps(usage)
if manual_key not in usage_text or resilient_key not in usage_text:
    raise RuntimeError("Usage groups do not contain scenario services")
code, audits = call("GET", f"/api/admin/organizations/{organization_id}/audit-logs?resourceType=PROJECT_EXTERNAL_ACCESS&size=50", admin=True)
expect("external-policy-audit-query", code, 200)
if len(audits.get("items", [])) < 2:
    raise RuntimeError("External access audit events missing")

code, _ = call("PATCH", f"/api/admin/external-providers/{provider['id']}", {"enabled": False}, admin=True)
expect("provider-disabled-after-test", code, 200)
code, _ = call("PATCH", f"/api/admin/projects/{project['id']}/status", {
    "status": "SUSPENDED", "revokeActiveApiKeys": True
}, admin=True)
expect("project-suspended-after-test", code, 200)

print(json.dumps({
    "ok": True, "stamp": stamp, "organizationId": organization_id,
    "projectId": project["id"], "providerId": provider["id"],
    "requestCount": len(items), "routes": sorted([list(value) for value in routes], key=str),
    "mockCalls": mock_calls,
    "steps": results
}, ensure_ascii=False))