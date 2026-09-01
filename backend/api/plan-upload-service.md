# Plan Upload Service

## Purpose
Receive house plans and prepare them for AI analysis.

Flow:
1. User creates project.
2. User uploads image/PDF plan.
3. Backend stores original file.
4. AI analysis job starts.
5. Missing information is requested from user.

Rules:
- Never modify the original plan.
- Keep original file version.
- Every generated result links back to the source plan.
