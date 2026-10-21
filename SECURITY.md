# Security and privacy

This is a local demonstration prototype. Do not expose it directly to the public internet or enter real customer data.

## Known prototype boundaries

- Demo agent credentials are fixed in source code. They are not a production identity system.
- Not every backend endpoint enforces authentication or authorization. Some allow broad cross-origin access.
- File attachments, persistent state, training exports, and model configuration need a complete security review before deployment.
- Local JSON storage is not a production multi-tenant database or a distributed transaction system.
- Model output, user content, and images are untrusted inputs. Human verification remains necessary for safety-sensitive issues and business commitments.

This release binds the backend to `127.0.0.1` and disables training-call recording by default. Those defaults reduce accidental exposure but do not fix the boundaries above.

## Credentials

Store secrets only in server environment variables or the ignored backend `.env.local` file. Do not put API keys in frontend source, screenshots, commits, issues, or browser storage. `.env.example` contains empty fields only.

If a real key has ever been committed, revoke or rotate it with the provider. Deleting the latest file does not remove it from Git history.

## Customer data

Runtime data lives in the operating-system user's `.beautycare-service-os` directory, and browser storage also holds local state. Both are excluded from this distribution. Source fixtures are demonstration examples and should not be replaced with real customer exports.

Cloud AI tasks can send text and images to the configured model provider. Obtain appropriate consent and apply data minimization before using anything beyond synthetic examples.

## Reporting

For a public repository, configure a private security contact or GitHub private vulnerability reporting before accepting sensitive reports. Do not post keys or customer records in public issues. No maintainer identity or contact address has been invented in this distribution.
