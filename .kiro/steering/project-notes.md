---
inclusion: auto
---

# Project Notes & Reminders

## Pending Setup Steps
- GitHub webhook has NOT been created yet. It needs to be set up after the platform is deployed and exposed via ngrok/smee.io (after Task 17/18). The payload URL will be `https://<ngrok-url>/webhook/github`.

## Security Rules
- NEVER read the `.env` file. It contains secrets (API keys, tokens, passwords). Reference variables by name only, never by value.
- Use `.env.example` as the reference for what environment variables exist.
