# Project Storage Design

## User Space
Each user owns isolated projects.

Project contains:
- Original plan file
- Analysis data
- Questions and answers
- 3D model files
- Rendered images
- Versions

## Versioning
Every regeneration creates a new version instead of overwriting the previous result.
