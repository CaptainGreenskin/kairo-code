---
name: api-design
version: 1.0.0
category: CODE
triggers:
  - "design api"
  - "api design"
  - "/api"
---
# API Design

When designing REST APIs, follow these principles:

**Naming**
- Use nouns for resources: `/users`, `/orders`
- Use kebab-case for multi-word: `/order-items`
- Nest for relationships: `/users/{id}/orders`

**Methods**
- GET (read), POST (create), PUT (full update), PATCH (partial update), DELETE

**Response**
- 200 OK, 201 Created, 204 No Content
- 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 409 Conflict
- Error body: `{"error": "code", "message": "human-readable"}`

**Versioning**
- URL prefix: `/api/v1/...`

**Pagination**
- Query params: `?page=1&size=20`
- Response: `{"data": [...], "total": 100, "page": 1}`

Rules:
- Be consistent. Pick one convention and stick to it.
- Always document with examples — request body + response body.
- Design for the client, not the database schema.
