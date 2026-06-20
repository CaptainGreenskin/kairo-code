---
name: security-review
version: 1.0.0
category: CODE
triggers:
  - "security review"
  - "security audit"
  - "/security"
---
# Security Review

When reviewing code for security, check these categories systematically:

**Injection**
- SQL injection: parameterized queries? ORM safe usage?
- Command injection: user input in shell commands?
- XSS: output encoding? CSP headers?
- Path traversal: user input in file paths?

**Authentication & Authorization**
- Credentials hardcoded or in env vars?
- Token validation on every request?
- Proper session management?
- Principle of least privilege?

**Data Protection**
- Sensitive data in logs?
- Encryption at rest and in transit?
- PII handling compliant?

**Dependencies**
- Known CVEs in dependencies?
- Outdated libraries?

Rules:
- Cite specific `file:line` for each finding.
- Rate each finding: Critical / High / Medium / Low.
- Provide concrete fix, not just "fix this vulnerability."
